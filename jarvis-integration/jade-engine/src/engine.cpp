#include "jade/engine.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <random>
#include <stdexcept>

namespace jade {
Engine::Layer::Layer(int c,int d,int h,int f):
    norm1(c,d),q(c,d),k(c,d),v(c,d),scores(h*c,c),probabilities(h*c,c),
    joined(c,d),projection(c,d),residual(c,d),norm2(c,d),pre_gelu(c,f),
    gelu_value(c,f),ffn_output(c,d){}

Engine::Engine(Config c,Backend backend,std::uint64_t seed):config_(c),backend_(backend),
    parameters_(1,c.parameter_count()),gradients_(1,c.parameter_count()),
    first_(1,c.parameter_count()),second_(1,c.parameter_count()),
    final_norm_(c.context,c.width),logits_(c.context,c.vocab),d_logits_(c.context,c.vocab),
    d_state_(c.context,c.width),d_next_(c.context,c.width),d_residual_(c.context,c.width),
    d_joined_(c.context,c.width),d_q_(c.context,c.width),d_k_(c.context,c.width),
    d_v_(c.context,c.width),d_norm_(c.context,c.width),d_ffn_(c.context,c.ffn),
    d_pre_(c.context,c.ffn),d_hidden_(c.context,c.width),d_probability_(1,c.context){
    std::size_t offset=0;
    auto add=[&](const std::string& name,int rows,int cols){
        auto result=offset;layout_.push_back({name,rows,cols,offset});offset+=std::size_t(rows)*cols;return result;
    };
    embedding_offset_=add("embedding.weight",c.vocab,c.width);
    position_offset_=add("position.weight",c.context,c.width);
    for(int l=0;l<c.layers;l++){
        const std::string name="blocks."+std::to_string(l)+".";
        block_offsets_.push_back({add(name+"attention.q.weight",c.width,c.width),
                                  add(name+"attention.k.weight",c.width,c.width),
                                  add(name+"attention.v.weight",c.width,c.width),
                                  add(name+"attention.out.weight",c.width,c.width),
                                  add(name+"ffn.up.weight",c.width,c.ffn),
                                  add(name+"ffn.down.weight",c.ffn,c.width)});
    }
    head_offset_=add("lmHead.weight",c.width,c.vocab);
    if(offset!=c.parameter_count())throw std::logic_error("Parameter layout count mismatch");
    states_.reserve(c.layers+1);
    for(int l=0;l<=c.layers;l++)states_.emplace_back(c.context,c.width);
    cache_.reserve(c.layers);
    for(int l=0;l<c.layers;l++)cache_.emplace_back(c.context,c.width,c.heads,c.ffn);
    std::mt19937_64 rng(seed);
    std::normal_distribution<float> normal(0.f,.02f);
    for(std::size_t i=0;i<parameters_.size();i++)parameters_.data()[i]=normal(rng);
}
const Parameter& Engine::parameter(const std::string& name) const {
    for(const auto& item:layout_)if(item.name==name)return item;
    throw std::invalid_argument("Unknown JADE parameter: "+name);
}
std::size_t Engine::workspace_bytes() const {
    std::size_t elements=final_norm_.size()+logits_.size()+d_logits_.size()+d_state_.size()+d_next_.size()+
        d_residual_.size()+d_joined_.size()+d_q_.size()+d_k_.size()+d_v_.size()+d_norm_.size()+
        d_ffn_.size()+d_pre_.size()+d_hidden_.size()+d_probability_.size();
    for(const auto& state:states_)elements+=state.size();
    for(const auto& layer:cache_)elements+=layer.norm1.size()+layer.q.size()+layer.k.size()+layer.v.size()+
        layer.scores.size()+layer.probabilities.size()+layer.joined.size()+layer.projection.size()+
        layer.residual.size()+layer.norm2.size()+layer.pre_gelu.size()+layer.gelu_value.size()+layer.ffn_output.size();
    return elements*sizeof(float);
}
void Engine::zero_grad(){gradients_.fill(0);}

void Engine::forward_hidden(const int* input,int t){
    const int d=config_.width,f=config_.ffn,c=config_.context,h=config_.heads,head_dim=d/h;
    if(!input||t<1||t>c)throw std::invalid_argument("Invalid JADE context");
    float* state=states_[0].data();
    for(int i=0;i<t;i++){
        if(input[i]<0||input[i]>=config_.vocab)throw std::invalid_argument("Invalid token ID");
        for(int j=0;j<d;j++)state[i*d+j]=parameters_.data()[embedding_offset_+std::size_t(input[i])*d+j]
                +parameters_.data()[position_offset_+i*d+j];
    }
    for(int l=0;l<config_.layers;l++){
        auto& a=cache_[l];const auto& off=block_offsets_[l];
        const float* x=states_[l].data();float* next=states_[l+1].data();
        rmsnorm(x,a.norm1.data(),t,d);
        gemm(backend_,false,false,t,d,d,a.norm1.data(),parameters_.data()+off[0],a.q.data());
        gemm(backend_,false,false,t,d,d,a.norm1.data(),parameters_.data()+off[1],a.k.data());
        gemm(backend_,false,false,t,d,d,a.norm1.data(),parameters_.data()+off[2],a.v.data());
        a.joined.fill(0);a.probabilities.fill(0);a.scores.fill(-1e9f);
        for(int head=0;head<h;head++)for(int i=0;i<t;i++){
            float* score=a.scores.data()+head*c*c+i*c;
            float* probability=a.probabilities.data()+head*c*c+i*c;
            for(int j=0;j<=i;j++){
                float sum=0;
                for(int channel=0;channel<head_dim;channel++){
                    const int col=head*head_dim+channel;
                    sum+=a.q.data()[i*d+col]*a.k.data()[j*d+col];
                }
                score[j]=sum/std::sqrt(float(head_dim));
            }
            softmax(score,probability,i+1);
            for(int channel=0;channel<head_dim;channel++){
                const int col=head*head_dim+channel;
                float sum=0;
                for(int j=0;j<=i;j++)sum+=probability[j]*a.v.data()[j*d+col];
                a.joined.data()[i*d+col]=sum;
            }
        }
        gemm(backend_,false,false,t,d,d,a.joined.data(),parameters_.data()+off[3],a.projection.data());
        for(int i=0;i<t*d;i++)a.residual.data()[i]=x[i]+a.projection.data()[i];
        rmsnorm(a.residual.data(),a.norm2.data(),t,d);
        gemm(backend_,false,false,t,f,d,a.norm2.data(),parameters_.data()+off[4],a.pre_gelu.data());
        for(int i=0;i<t*f;i++)a.gelu_value.data()[i]=gelu(a.pre_gelu.data()[i]);
        gemm(backend_,false,false,t,d,f,a.gelu_value.data(),parameters_.data()+off[5],a.ffn_output.data());
        for(int i=0;i<t*d;i++)next[i]=a.residual.data()[i]+a.ffn_output.data()[i];
    }
    rmsnorm(states_.back().data(),final_norm_.data(),t,d);
    gemm(backend_,false,false,t,config_.vocab,d,final_norm_.data(),parameters_.data()+head_offset_,logits_.data());
}
float Engine::forward_loss(const int* input,const int* target,int t){
    forward_hidden(input,t);
    if(!target)throw std::invalid_argument("Missing targets");
    double total=0;
    for(int i=0;i<t;i++){
        if(target[i]<0||target[i]>=config_.vocab)throw std::invalid_argument("Invalid target ID");
        const float* row=logits_.data()+std::size_t(i)*config_.vocab;
        float maximum=-std::numeric_limits<float>::infinity();
        for(int j=0;j<config_.vocab;j++)maximum=std::max(maximum,row[j]);
        double sum=0;for(int j=0;j<config_.vocab;j++)sum+=std::exp(row[j]-maximum);
        total+=(maximum-row[target[i]])+std::log(sum);
    }
    const float loss=float(total/t);
    if(!std::isfinite(loss))throw std::runtime_error("Nonfinite JADE loss");
    return loss;
}

float Engine::backward(const int* input,const int* target,int t){
    const float loss=forward_loss(input,target,t);
    const int d=config_.width,f=config_.ffn,c=config_.context,h=config_.heads,vocab=config_.vocab,head_dim=d/h;
    last_length_=t;
    for(int i=0;i<t;i++){
        float* row=d_logits_.data()+std::size_t(i)*vocab;
        softmax(logits_.data()+std::size_t(i)*vocab,row,vocab);
        row[target[i]]-=1;
        for(int j=0;j<vocab;j++)row[j]/=t;
    }
    gemm(backend_,true,false,d,vocab,t,final_norm_.data(),d_logits_.data(),
         gradients_.data()+head_offset_,1,1);
    gemm(backend_,false,true,t,d,vocab,d_logits_.data(),parameters_.data()+head_offset_,d_hidden_.data());
    rmsnorm_backward(states_.back().data(),d_hidden_.data(),d_state_.data(),t,d);
    for(int l=config_.layers-1;l>=0;l--){
        auto& a=cache_[l];const auto& off=block_offsets_[l];
        const float* x=states_[l].data();
        gemm(backend_,true,false,f,d,t,a.gelu_value.data(),d_state_.data(),gradients_.data()+off[5],1,1);
        gemm(backend_,false,true,t,f,d,d_state_.data(),parameters_.data()+off[5],d_ffn_.data());
        for(int i=0;i<t*f;i++)d_pre_.data()[i]=d_ffn_.data()[i]*gelu_derivative(a.pre_gelu.data()[i]);
        gemm(backend_,true,false,d,f,t,a.norm2.data(),d_pre_.data(),gradients_.data()+off[4],1,1);
        gemm(backend_,false,true,t,d,f,d_pre_.data(),parameters_.data()+off[4],d_norm_.data());
        rmsnorm_backward(a.residual.data(),d_norm_.data(),d_next_.data(),t,d);
        for(int i=0;i<t*d;i++)d_residual_.data()[i]=d_state_.data()[i]+d_next_.data()[i];

        gemm(backend_,true,false,d,d,t,a.joined.data(),d_residual_.data(),gradients_.data()+off[3],1,1);
        gemm(backend_,false,true,t,d,d,d_residual_.data(),parameters_.data()+off[3],d_joined_.data());
        d_q_.fill(0);d_k_.fill(0);d_v_.fill(0);
        for(int head=0;head<h;head++)for(int i=0;i<t;i++){
            const float* p=a.probabilities.data()+head*c*c+i*c;
            float* dp=d_probability_.data();
            for(int j=0;j<=i;j++){
                float dot=0;
                for(int channel=0;channel<head_dim;channel++){
                    const int col=head*head_dim+channel;
                    dot+=d_joined_.data()[i*d+col]*a.v.data()[j*d+col];
                    d_v_.data()[j*d+col]+=p[j]*d_joined_.data()[i*d+col];
                }
                dp[j]=dot;
            }
            float weighted=0;for(int j=0;j<=i;j++)weighted+=dp[j]*p[j];
            for(int j=0;j<=i;j++){
                const float ds=p[j]*(dp[j]-weighted)/std::sqrt(float(head_dim));
                for(int channel=0;channel<head_dim;channel++){
                    const int col=head*head_dim+channel;
                    d_q_.data()[i*d+col]+=ds*a.k.data()[j*d+col];
                    d_k_.data()[j*d+col]+=ds*a.q.data()[i*d+col];
                }
            }
        }
        gemm(backend_,true,false,d,d,t,a.norm1.data(),d_q_.data(),gradients_.data()+off[0],1,1);
        gemm(backend_,true,false,d,d,t,a.norm1.data(),d_k_.data(),gradients_.data()+off[1],1,1);
        gemm(backend_,true,false,d,d,t,a.norm1.data(),d_v_.data(),gradients_.data()+off[2],1,1);
        gemm(backend_,false,true,t,d,d,d_q_.data(),parameters_.data()+off[0],d_norm_.data());
        gemm(backend_,false,true,t,d,d,d_k_.data(),parameters_.data()+off[1],d_norm_.data(),1,1);
        gemm(backend_,false,true,t,d,d,d_v_.data(),parameters_.data()+off[2],d_norm_.data(),1,1);
        rmsnorm_backward(x,d_norm_.data(),d_next_.data(),t,d);
        for(int i=0;i<t*d;i++)d_state_.data()[i]=d_residual_.data()[i]+d_next_.data()[i];
    }
    for(int i=0;i<t;i++)for(int j=0;j<d;j++){
        const float upstream=d_state_.data()[i*d+j];
        gradients_.data()[embedding_offset_+std::size_t(input[i])*d+j]+=upstream;
        gradients_.data()[position_offset_+i*d+j]+=upstream;
    }
    return loss;
}
void Engine::adamw(const AdamWConfig& s,int examples){
    for(float value:{s.learning_rate,s.beta1,s.beta2,s.epsilon,s.decay,s.clip})
        if(!std::isfinite(value))throw std::invalid_argument("Nonfinite AdamW setting");
    if(examples<1||last_length_<1||s.learning_rate<=0||s.beta1<0||s.beta1>=1||
       s.beta2<0||s.beta2>=1||s.epsilon<=0||s.decay<0||s.clip<0)
        throw std::invalid_argument("Invalid AdamW step");
    const std::size_t n=parameters_.size();
    double squared=0;
    for(std::size_t i=0;i<n;i++){
        const double value=double(gradients_.data()[i])/examples;
        if(!std::isfinite(value))throw std::runtime_error("Nonfinite gradient");
        squared+=value*value;
    }
    const double norm=std::sqrt(squared),clip=s.clip>0&&norm>s.clip?s.clip/norm:1;
    if(steps_==std::numeric_limits<std::uint64_t>::max()||
       std::uint64_t(examples)*last_length_>std::numeric_limits<std::uint64_t>::max()-positions_)
        throw std::overflow_error("Training counter overflow");
    const auto next_step=steps_+1;
    const double bias1=1-std::pow(s.beta1,double(next_step)),bias2=1-std::pow(s.beta2,double(next_step));
    for(std::size_t i=0;i<n;i++){
        const float old=parameters_.data()[i],g=float((double(gradients_.data()[i])/examples)*clip);
        const float m=first_.data()[i]=s.beta1*first_.data()[i]+(1-s.beta1)*g;
        const float v=second_.data()[i]=s.beta2*second_.data()[i]+(1-s.beta2)*g*g;
        const float updated=old-s.learning_rate*float((m/bias1)/(std::sqrt(v/bias2)+s.epsilon))
                -s.learning_rate*s.decay*old;
        if(!std::isfinite(updated)||!std::isfinite(m)||!std::isfinite(v))
            throw std::runtime_error("Nonfinite AdamW state");
        parameters_.data()[i]=updated;
    }
    steps_=next_step;positions_+=std::uint64_t(examples)*last_length_;
}
std::vector<int> Engine::generate(const std::vector<int>& prompt,int new_tokens){
    if(prompt.empty()||int(prompt.size())>config_.context||new_tokens<0)throw std::invalid_argument("Invalid generation request");
    std::vector<int> all=prompt,output;
    while(int(output.size())<new_tokens&&int(all.size())<config_.context){
        forward_hidden(all.data(),int(all.size()));
        const float* row=logits_.data()+(all.size()-1)*config_.vocab;
        int best=0;for(int i=1;i<config_.vocab;i++)if(row[i]>row[best])best=i;
        all.push_back(best);output.push_back(best);
    }
    return output;
}
std::vector<Trace> Engine::trace(int l,int t) const {
    if(l<0||l>=config_.layers||t<1||t>config_.context)throw std::invalid_argument("Invalid trace layer/length");
    const auto& a=cache_[l];const auto td=std::size_t(t)*config_.width,tf=std::size_t(t)*config_.ffn;
    return {{"embedding",states_[0].data(),td},{"norm1",a.norm1.data(),td},
            {"q",a.q.data(),td},{"k",a.k.data(),td},{"v",a.v.data(),td},
            {"masked_scores",a.scores.data(),std::size_t(config_.heads)*config_.context*config_.context},
            {"probabilities",a.probabilities.data(),std::size_t(config_.heads)*config_.context*config_.context},
            {"joined",a.joined.data(),td},{"att_projection",a.projection.data(),td},
            {"residual1",a.residual.data(),td},{"norm2",a.norm2.data(),td},
            {"ffn_pre",a.pre_gelu.data(),tf},{"gelu",a.gelu_value.data(),tf},
            {"ffn_out",a.ffn_output.data(),td},{"residual2",states_[l+1].data(),td},
            {"final_norm",final_norm_.data(),td},{"logits",logits_.data(),std::size_t(t)*config_.vocab}};
}
} // namespace jade
