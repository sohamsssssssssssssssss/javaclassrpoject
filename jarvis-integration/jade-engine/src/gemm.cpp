#include "jade/engine.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#ifdef JADE_HAS_ACCELERATE
#include <Accelerate/Accelerate.h>
#endif

namespace jade {
namespace {
std::uint64_t checked_add(std::uint64_t a,std::uint64_t b){
    if(b>std::numeric_limits<std::uint64_t>::max()-a)throw std::overflow_error("JADE size addition overflow");
    return a+b;
}
std::uint64_t checked_mul(std::uint64_t a,std::uint64_t b){
    if(b&&a>std::numeric_limits<std::uint64_t>::max()/b)throw std::overflow_error("JADE size multiplication overflow");
    return a*b;
}
std::uint64_t exact_parameters(Config c){
    const auto d=std::uint64_t(c.width),v=std::uint64_t(c.vocab),context=std::uint64_t(c.context),
               f=std::uint64_t(c.ffn),layers=std::uint64_t(c.layers);
    const auto embeddings=checked_mul(checked_add(checked_mul(2,v),context),d);
    const auto block=checked_add(checked_mul(4,checked_mul(d,d)),checked_mul(2,checked_mul(d,f)));
    return checked_add(embeddings,checked_mul(layers,block));
}
}
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

std::size_t Config::parameter_count(const EngineeringLimits& limits) const {
    validate(limits);
    return static_cast<std::size_t>(exact_parameters(*this));
}
void Config::validate(const EngineeringLimits& limits) const {
    if(vocab<1||vocab>65536||context<1||context>4096||width<1||width>4096||
       heads<1||heads>width||width%heads||layers<1||layers>64||ffn<1||ffn>4096)
        throw std::invalid_argument("Invalid JADE architecture");
    if(!limits.max_parameters||limits.max_parameters>60'000'000||
       !limits.max_peak_bytes||limits.max_peak_bytes>2ull*1024*1024*1024)
        throw std::invalid_argument("Invalid engineering safety limits");
    if(exact_parameters(*this)>limits.max_parameters)
        throw std::invalid_argument("Engineering parameter ceiling exceeded");
}
MemoryPlan plan_memory(Config c,const EngineeringLimits& limits){
    const auto p=std::uint64_t(c.parameter_count(limits)),bytes=checked_mul(p,sizeof(float));
    const auto context=std::uint64_t(c.context),width=std::uint64_t(c.width),ffn=std::uint64_t(c.ffn),
               layers=std::uint64_t(c.layers),heads=std::uint64_t(c.heads),vocab=std::uint64_t(c.vocab);
    // Exact allocation formula for Engine's reusable states, layer caches and scratch tensors.
    auto workspace=checked_mul(checked_add(11,checked_mul(10,layers)),checked_mul(context,width));
    workspace=checked_add(workspace,checked_mul(checked_add(2,checked_mul(2,layers)),checked_mul(context,ffn)));
    workspace=checked_add(workspace,checked_mul(2,checked_mul(context,vocab)));
    workspace=checked_add(workspace,checked_mul(checked_mul(2,layers),checked_mul(heads,checked_mul(context,context))));
    workspace=checked_mul(checked_add(workspace,context),sizeof(float));
    const auto persistent=checked_mul(4,bytes),training=checked_add(persistent,workspace);
    const auto load=checked_add(training,checked_mul(3,bytes));
    const auto peak=checked_add(load,64ull*1024*1024); // Explicit engineering margin for runtime and BLAS.
    if(peak>limits.max_peak_bytes)throw std::invalid_argument("Engineering memory budget exceeded");
    return {bytes,bytes,bytes,bytes,persistent,workspace,checked_add(68,checked_mul(12,p)),
            training,load,peak};
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
