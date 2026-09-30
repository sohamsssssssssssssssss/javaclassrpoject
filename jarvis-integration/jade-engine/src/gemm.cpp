#include "jade/engine.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#ifdef JADE_HAS_ACCELERATE
#include <Accelerate/Accelerate.h>
#endif

namespace jade {
AllocationStats Tensor::stats_{};
Tensor::Tensor(std::size_t rows,std::size_t columns):rows_(rows),columns_(columns) {
    if (!rows || !columns || rows>std::numeric_limits<std::size_t>::max()/columns ||
        rows*columns>std::numeric_limits<std::size_t>::max()/sizeof(float))
        throw std::overflow_error("Invalid tensor dimensions");
    values_.resize(rows*columns);
    const auto bytes=values_.size()*sizeof(float);
    stats_.current_bytes+=bytes;
    stats_.peak_bytes=std::max(stats_.peak_bytes,stats_.current_bytes);
    ++stats_.allocations;
}
Tensor::~Tensor(){stats_.current_bytes-=values_.size()*sizeof(float);}
Tensor::Tensor(Tensor&& other) noexcept:rows_(other.rows_),columns_(other.columns_),values_(std::move(other.values_)){
    other.rows_=other.columns_=0;
}
Tensor& Tensor::operator=(Tensor&& other) noexcept {
    if(this!=&other){
        stats_.current_bytes-=values_.size()*sizeof(float);
        rows_=other.rows_;columns_=other.columns_;values_=std::move(other.values_);
        other.rows_=other.columns_=0;
    }
    return *this;
}
void Tensor::fill(float value){std::fill(values_.begin(),values_.end(),value);}

std::size_t Config::parameter_count() const {
    validate();
    const auto d=std::uint64_t(width),v=std::uint64_t(vocab),c=std::uint64_t(context),
               f=std::uint64_t(ffn),l=std::uint64_t(layers);
    return static_cast<std::size_t>((2*v+c)*d+l*(4*d*d+2*d*f));
}
void Config::validate() const {
    if(vocab<1||vocab>65536||context<1||context>4096||width<1||width>4096||
       heads<1||heads>width||width%heads||layers<1||layers>64||ffn<1||ffn>4096)
        throw std::invalid_argument("Invalid JADE architecture");
    const auto d=std::uint64_t(width),v=std::uint64_t(vocab),c=std::uint64_t(context),
               f=std::uint64_t(ffn),l=std::uint64_t(layers);
    if((2*v+c)*d+l*(4*d*d+2*d*f)>5500000)
        throw std::invalid_argument("Loop 6A parameter ceiling exceeded");
}

void gemm(Backend backend,bool ta,bool tb,int m,int n,int k,
          const float* a,const float* b,float* out,float alpha,float beta){
    if(m<1||n<1||k<1||!a||!b||!out)throw std::invalid_argument("Invalid GEMM operands");
    if(backend==Backend::Accelerate){
#ifdef JADE_HAS_ACCELERATE
        cblas_sgemm(CblasRowMajor,ta?CblasTrans:CblasNoTrans,tb?CblasTrans:CblasNoTrans,
                    m,n,k,alpha,a,ta?m:k,b,tb?k:n,beta,out,n);
        return;
#else
        throw std::runtime_error("Accelerate unavailable");
#endif
    }
    const auto count=std::size_t(m)*std::size_t(n);
    if(beta==0)std::fill_n(out,count,0.f);
    else for(std::size_t i=0;i<count;i++)out[i]*=beta;
    if(!ta&&!tb){
        for(int i=0;i<m;i++)for(int p=0;p<k;p++){
            const float value=alpha*a[i*k+p];
            for(int j=0;j<n;j++)out[i*n+j]+=value*b[p*n+j];
        }
    }else{
        for(int i=0;i<m;i++)for(int j=0;j<n;j++){
            float sum=0;
            for(int p=0;p<k;p++)sum+=(ta?a[p*m+i]:a[i*k+p])*(tb?b[j*k+p]:b[p*n+j]);
            out[i*n+j]+=alpha*sum;
        }
    }
}

void rmsnorm(const float* x,float* out,int rows,int width,float epsilon){
    if(rows<1||width<1||!std::isfinite(epsilon)||epsilon<=0)throw std::invalid_argument("Invalid RMSNorm shape");
    for(int r=0;r<rows;r++){
        double square=0;
        for(int c=0;c<width;c++)square+=double(x[r*width+c])*x[r*width+c];
        const float inverse=float(1.0/std::sqrt(square/width+epsilon));
        for(int c=0;c<width;c++)out[r*width+c]=x[r*width+c]*inverse;
    }
}
void rmsnorm_backward(const float* x,const float* upstream,float* out,int rows,int width,float epsilon){
    if(rows<1||width<1||!std::isfinite(epsilon)||epsilon<=0)throw std::invalid_argument("Invalid RMSNorm backward shape");
    for(int r=0;r<rows;r++){
        double square=0,dot=0;
        for(int c=0;c<width;c++){
            const auto i=r*width+c;
            square+=double(x[i])*x[i];dot+=double(x[i])*upstream[i];
        }
        const float inverse=float(1.0/std::sqrt(square/width+epsilon));
        const double factor=(dot/width)*inverse*inverse*inverse;
        for(int c=0;c<width;c++){
            const auto i=r*width+c;out[i]=float(upstream[i]*inverse-x[i]*factor);
        }
    }
}
float gelu(float x){
    const float t=std::tanh(0.7978845608028654f*(x+0.044715f*x*x*x));
    return 0.5f*x*(1+t);
}
float gelu_derivative(float x){
    const float a=0.7978845608028654f,b=0.044715f;
    const float t=std::tanh(a*(x+b*x*x*x));
    if(std::abs(t)==1.f)return 0.5f*(1+t); // Saturated tanh has zero derivative.
    return 0.5f*(1+t)+0.5f*x*(1-t*t)*a*(1+3*b*x*x);
}
void softmax(const float* input,float* output,int size){
    if(size<1)throw std::invalid_argument("Empty softmax");
    float maximum=-std::numeric_limits<float>::infinity();
    for(int i=0;i<size;i++){
        if(!std::isfinite(input[i]))throw std::invalid_argument("Nonfinite softmax input");
        maximum=std::max(maximum,input[i]);
    }
    double sum=0;
    for(int i=0;i<size;i++){output[i]=std::exp(input[i]-maximum);sum+=output[i];}
    for(int i=0;i<size;i++)output[i]=float(output[i]/sum);
}
} // namespace jade
