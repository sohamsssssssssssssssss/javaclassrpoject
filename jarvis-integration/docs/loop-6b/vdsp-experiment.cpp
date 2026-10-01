#define ACCELERATE_NEW_LAPACK
#include <Accelerate/Accelerate.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>
using clock_type=std::chrono::steady_clock;
static constexpr int n=5251072;
struct State{std::vector<float> p,g,m,v,scratch;State():p(n),g(n),m(n),v(n),scratch(n){for(int i=0;i<n;i++){p[i]=float((i%97)-48)*.001f;g[i]=float((i%103)-51)*.0001f;m[i]=float((i%53)-26)*.00001f;v[i]=float((i%79)+1)*.000001f;}}};
static void scalar(State& s,int step){
 const float b1=.9f,b2=.999f,lr=.001f,decay=.01f,eps=1e-8f;
 const double ib1=1/(1-std::pow(b1,double(step))),ib2=1/(1-std::pow(b2,double(step)));
 for(int i=0;i<n;i++){
  const float old=s.p[i],g=s.g[i],m=s.m[i]=b1*s.m[i]+(1-b1)*g,v=s.v[i]=b2*s.v[i]+(1-b2)*g*g;
  s.p[i]=old-lr*float((m*ib1)/(std::sqrt(v*ib2)+eps))-lr*decay*old;
 }
}
static void vector_sqrt(State& s,int step){
 const float b1=.9f,b2=.999f,lr=.001f,decay=.01f,eps=1e-8f;
 const double ib1=1/(1-std::pow(b1,double(step))),ib2=1/(1-std::pow(b2,double(step)));
 for(int i=0;i<n;i++){const float g=s.g[i];s.m[i]=b1*s.m[i]+(1-b1)*g;s.v[i]=b2*s.v[i]+(1-b2)*g*g;}
 const float factor=float(ib2);vDSP_vsmul(s.v.data(),1,&factor,s.scratch.data(),1,n);
 const int count=n;vvsqrtf(s.scratch.data(),s.scratch.data(),&count);
 for(int i=0;i<n;i++){const float old=s.p[i];s.p[i]=old-lr*float((s.m[i]*ib1)/(double(s.scratch[i])+eps))-lr*decay*old;}
}
int main(){State a,b;double ta[9],tb[9];for(int i=1;i<=9;i++){
 auto start=clock_type::now();scalar(a,i);ta[i-1]=std::chrono::duration<double,std::milli>(clock_type::now()-start).count();
 start=clock_type::now();vector_sqrt(b,i);tb[i-1]=std::chrono::duration<double,std::milli>(clock_type::now()-start).count();
}
 double error=0;for(int i=0;i<n;i++)error=std::max(error,double(std::abs(a.p[i]-b.p[i])));
 std::sort(ta+2,ta+9);std::sort(tb+2,tb+9);
 printf("scalar_median_ms=%.6f vdsp_sqrt_median_ms=%.6f max_parameter_abs_difference=%.9g extra_workspace_bytes=%zu\n",ta[5],tb[5],error,b.scratch.size()*sizeof(float));
}
