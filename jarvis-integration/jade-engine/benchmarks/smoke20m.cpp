#include "jade/engine.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <sys/resource.h>

using namespace jade;
using Clock=std::chrono::steady_clock;
namespace {
constexpr Config shape{1024,32,448,8,8,1792};
std::uint64_t peak_rss(){rusage usage{};getrusage(RUSAGE_SELF,&usage);
#ifdef __APPLE__
    return usage.ru_maxrss;
#else
    return std::uint64_t(usage.ru_maxrss)*1024;
#endif
}
void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
void close(double value,double expected,double tolerance,const char* message){
    require(std::isfinite(value)&&std::abs(value-expected)<=tolerance,message);
}
std::uint64_t state_hash(const Engine& engine){
    std::uint64_t hash=14695981039346656037ull;
    for(const float* buffer:{engine.weights(),engine.first_moments(),engine.second_moments()})
        for(std::size_t i=0;i<shape.parameter_count();i++){
            std::uint32_t bits;std::memcpy(&bits,buffer+i,sizeof(bits));
            hash^=bits;hash*=1099511628211ull;
        }
    return hash;
}
bool checkpoint_payload_matches(const std::filesystem::path& path,const Engine& engine){
    std::ifstream in(path,std::ios::binary);in.seekg(64); // Version-1 header before p/m/v payload.
    std::array<char,65536> chunk{};
    for(const float* buffer:{engine.weights(),engine.first_moments(),engine.second_moments()}){
        const char* bytes=reinterpret_cast<const char*>(buffer);
        std::size_t remaining=shape.parameter_count()*sizeof(float);
        while(remaining){
            const auto count=std::min(remaining,chunk.size());
            in.read(chunk.data(),std::streamsize(count));
            if(!in||std::memcmp(chunk.data(),bytes,count))return false;
            remaining-=count;bytes+=count;
        }
    }
    return true;
}
const Trace& named(const std::vector<Trace>& traces,const char* name){
    for(const auto& trace:traces)if(trace.name==name)return trace;
    throw std::runtime_error("Missing forward trace");
}
void large_forward_checks(Engine& model,const int* input,const int* target){
    const float loss=model.forward_loss(input,target,32);
    require(std::isfinite(loss),"Nonfinite large-shape forward loss");
    const auto embedding=model.parameter("embedding.weight"),position=model.parameter("position.weight");
    close(named(model.trace(0,32),"embedding").values[0],
          double(model.weights()[embedding.offset+std::size_t(input[0])*448])+model.weights()[position.offset],1e-6,
          "Large-shape embedding mismatch");
    for(int layer=0;layer<8;layer++){
        const auto traces=model.trace(layer,32);
        for(const auto& trace:traces)for(std::size_t i=0;i<trace.size;i++)
            require(std::isfinite(trace.values[i]),"Nonfinite large-shape intermediate");
        require(named(traces,"q").size==32u*448&&named(traces,"ffn_pre").size==32u*1792,
                "Large-shape projection dimensions");
        const auto& probabilities=named(traces,"probabilities");
        for(int head=0;head<8;head++)for(int row=0;row<32;row++){
            double total=0;
            for(int column=0;column<32;column++){
                const float value=probabilities.values[head*1024+row*32+column];
                if(column>row)require(value==0,"Future attention probability");
                else total+=value;
            }
            close(total,1,2e-6,"Causal attention row sum");
        }
        const auto& q=named(traces,"q"),&k=named(traces,"k"),&scores=named(traces,"masked_scores");
        double dot=0;for(int channel=0;channel<56;channel++)
            dot+=double(q.values[31*448+7*56+channel])*k.values[17*448+7*56+channel];
        close(scores.values[7*1024+31*32+17],dot/std::sqrt(56.),2e-4,"Last-head score indexing");
        const auto& joined=named(traces,"joined"),&projected=named(traces,"att_projection");
        const auto out=model.parameter("blocks."+std::to_string(layer)+".attention.out.weight");
        double projected_reference=0;
        for(int channel=0;channel<448;channel++)
            projected_reference+=double(joined.values[31*448+channel])*model.weights()[out.offset+channel*448+447];
        close(projected.values[31*448+447],projected_reference,3e-4,"Attention output projection");
        const auto& norm2=named(traces,"norm2"),&pre=named(traces,"ffn_pre"),&gelu_values=named(traces,"gelu");
        const auto up=model.parameter("blocks."+std::to_string(layer)+".ffn.up.weight");
        double up_reference=0;
        for(int channel=0;channel<448;channel++)
            up_reference+=double(norm2.values[31*448+channel])*model.weights()[up.offset+channel*1792+1791];
        close(pre.values[31*1792+1791],up_reference,5e-4,"FFN up projection");
        close(gelu_values.values[31*1792+1791],gelu(pre.values[31*1792+1791]),1e-6,"FFN GELU");
        const auto& down_values=named(traces,"ffn_out");
        const auto down=model.parameter("blocks."+std::to_string(layer)+".ffn.down.weight");
        double down_reference=0;
        for(int channel=0;channel<1792;channel++)
            down_reference+=double(gelu_values.values[31*1792+channel])*model.weights()[down.offset+channel*448+447];
        close(down_values.values[31*448+447],down_reference,8e-4,"FFN down projection");
    }
    const auto traces=model.trace(7,32);
    const auto& final_norm=named(traces,"final_norm"),&logits=named(traces,"logits");
    const auto lm=model.parameter("lmHead.weight");
    double logit_reference=0;
    for(int channel=0;channel<448;channel++)
        logit_reference+=double(final_norm.values[31*448+channel])*model.weights()[lm.offset+channel*1024+1023];
    close(logits.values[31*1024+1023],logit_reference,5e-4,"LM head projection");
    double ce=0;
    for(int row=0;row<32;row++){
        const float* values=logits.values+row*1024;
        float maximum=*std::max_element(values,values+1024);
        double sum=0;for(int i=0;i<1024;i++)sum+=std::exp(values[i]-maximum);
        ce+=(maximum-values[target[row]])+std::log(sum);
    }
    close(loss,ce/32,2e-5,"Large-shape cross entropy");
    std::cout<<"FORWARD_CHECK layers=8 heads=8 headWidth=56 causal=PASS projections=PASS loss="<<loss<<"\n";
}
float average_loss(Engine& model,const std::array<std::array<int,33>,2>& data){
    return .5f*(model.forward_loss(data[0].data(),data[0].data()+1,32)+
                model.forward_loss(data[1].data(),data[1].data()+1,32));
}
float train_step(Engine& model,const std::array<std::array<int,33>,2>& data){
    model.zero_grad();float loss=0;
    for(const auto& row:data)loss+=model.backward(row.data(),row.data()+1,32)/2;
    model.adamw({},2);return loss;
}
}
int main(int argc,char** argv){
    try{
        if(argc!=3)throw std::invalid_argument("token-fixture-path checkpoint-directory");
        const auto planned=plan_memory(shape);
        require(shape.parameter_count()==20199424&&planned.persistent_bytes==323190784&&
                planned.workspace_bytes==10133632&&planned.checkpoint_bytes==242393156,
                "20M preflight identity mismatch");
        std::filesystem::create_directories(argv[2]);
        require(std::filesystem::space(argv[2]).available>2*planned.checkpoint_bytes+64ull*1024*1024,
                "Insufficient checkpoint space");
        std::array<std::array<int,33>,2> data{};std::ifstream tokens(argv[1]);
        for(auto& row:data)for(int& id:row)require(bool(tokens>>id)&&id>=0&&id<1024,"Invalid frozen token fixture");
        std::cout<<std::setprecision(10)<<"PREFLIGHT parameters="<<shape.parameter_count()
                 <<" persistent="<<planned.persistent_bytes<<" workspace="<<planned.workspace_bytes
                 <<" checkpoint="<<planned.checkpoint_bytes<<" engineeringPeak="<<planned.engineering_peak_bytes<<"\n";
        const std::filesystem::path checkpoint=std::filesystem::path(argv[2])/"20M-step4.jade";
        const std::filesystem::path next_checkpoint=std::filesystem::path(argv[2])/"20M-step5-compare.jade";
        std::uint64_t saved_hash=0,reference_next_hash=0,reference_steps=0,reference_positions=0;
        float step4_loss=0,reference_next_loss=0;
        {
            ConstructionRss construction{};Engine model(shape,Backend::Accelerate,26167,{},&construction);
            require(model.config().width/model.config().heads==56&&model.layout().size()==51,
                    "20M model structure mismatch");
            require(Tensor::allocations().current_bytes==planned.persistent_bytes+planned.workspace_bytes,
                    "Unexpected 20M tensor allocation");
            std::size_t offset=0;
            for(const auto& parameter:model.layout()){
                require(parameter.offset==offset&&parameter.rows>0&&parameter.columns>0,"Parameter layout gap or shape");
                offset+=std::size_t(parameter.rows)*parameter.columns;
            }
            require(offset==shape.parameter_count(),"Parameter layout count");
            for(std::size_t i=0;i<shape.parameter_count();i++)
                require(std::isfinite(model.weights()[i])&&model.first_moments()[i]==0&&
                        model.second_moments()[i]==0&&model.gradients()[i]==0,"Invalid random model state");
            std::cout<<"CONSTRUCT rssBefore="<<construction.before_model
                     <<" rssParameters="<<construction.after_parameters
                     <<" rssOptimizer="<<construction.after_optimizer
                     <<" rssWorkspace="<<construction.after_workspace
                     <<" peakRss="<<peak_rss()<<" tensorAllocations="<<Tensor::allocations().allocations<<"\n";
            large_forward_checks(model,data[0].data(),data[0].data()+1);
            const float initial=average_loss(model,data);
            std::cout<<"LOSS step=0 value="<<initial<<"\n";
            model.profile_enabled(true);
            const auto allocations_before=Tensor::allocations().allocations;
            const float old_head=model.weights()[model.parameter("lmHead.weight").offset];
            const auto start=Clock::now();train_step(model,data);
            const auto elapsed=std::chrono::duration<double,std::milli>(Clock::now()-start).count();
            double lanes[4]{};std::size_t nonzero=0;
            for(std::size_t i=0;i<shape.parameter_count();i++){
                const float gradient=model.gradients()[i];
                require(std::isfinite(gradient)&&std::isfinite(model.weights()[i])&&
                        std::isfinite(model.first_moments()[i])&&std::isfinite(model.second_moments()[i]),
                        "Nonfinite 20M training state");
                if(gradient!=0)nonzero++;
                const double value=double(gradient)*.5;lanes[i&3]+=value*value;
            }
            const double norm=std::sqrt((lanes[0]+lanes[1])+(lanes[2]+lanes[3]));
            const double clip=norm>1?1/norm:1;
            const auto head=model.parameter("lmHead.weight").offset;
            require(std::isfinite(norm)&&nonzero>0&&model.weights()[head]!=old_head&&
                    model.steps()==1&&model.positions()==64&&
                    Tensor::allocations().allocations==allocations_before,"Single-step update gate");
            close(model.first_moments()[head],.1*float(double(model.gradients()[head])*.5*clip),3e-6,
                  "20M clipped first moment");
            const auto& p=model.profile();
            std::cout<<"SINGLE_STEP totalMs="<<elapsed<<" forwardMs="<<p.forward_ns/1e6
                     <<" lossMs="<<p.loss_ns/1e6<<" backwardOtherMs="<<p.backward_ns/1e6
                     <<" gradAccumMs="<<p.grad_accum_ns/1e6<<" gradNormMs="<<p.grad_norm_ns/1e6
                     <<" clipMs="<<p.clip_ns/1e6<<" adamwUpdateMs="<<p.adamw_update_ns/1e6
                     <<" zeroMs="<<p.zero_ns/1e6<<" norm="<<norm<<" clipFactor="<<clip
                     <<" nonzeroGradients="<<nonzero<<" peakRss="<<peak_rss()<<"\n";
            model.profile_enabled(false);
            float previous=average_loss(model,data);
            std::cout<<"LOSS step=1 value="<<previous<<"\n";
            for(int step=2;step<=4;step++){
                train_step(model,data);previous=average_loss(model,data);
                require(std::isfinite(previous),"Nonfinite 20M micro loss");
                std::cout<<"LOSS step="<<step<<" value="<<previous<<"\n";
            }
            step4_loss=previous;
            require(model.steps()==4&&model.positions()==256&&step4_loss<initial,
                    "Bounded 20M micro-training failed");
            saved_hash=state_hash(model);reference_steps=model.steps();reference_positions=model.positions();
            model.save(checkpoint.string());
            require(std::filesystem::file_size(checkpoint)==planned.checkpoint_bytes&&
                    checkpoint_payload_matches(checkpoint,model),"20M checkpoint payload mismatch");
            std::cout<<"CHECKPOINT bytes="<<std::filesystem::file_size(checkpoint)<<" step="<<model.steps()
                     <<" positions="<<model.positions()<<" stateHash="<<saved_hash<<" peakRss="<<peak_rss()<<"\n";
            train_step(model,data);reference_next_loss=average_loss(model,data);
            reference_next_hash=state_hash(model);
            model.save(next_checkpoint.string());
        }
        ConstructionRss reloaded_rss{};Engine resumed(shape,Backend::Accelerate,999,{},&reloaded_rss);
        resumed.load(checkpoint.string());
        require(resumed.steps()==reference_steps&&resumed.positions()==reference_positions&&
                state_hash(resumed)==saved_hash&&checkpoint_payload_matches(checkpoint,resumed),
                "20M checkpoint reload identity mismatch");
        close(average_loss(resumed,data),step4_loss,0,"20M checkpoint forward mismatch");
        train_step(resumed,data);
        close(average_loss(resumed,data),reference_next_loss,0,"20M resumed forward mismatch");
        require(state_hash(resumed)==reference_next_hash&&checkpoint_payload_matches(next_checkpoint,resumed)&&
                resumed.steps()==5&&resumed.positions()==320,
                "20M resumed next-step mismatch");
        std::filesystem::remove(next_checkpoint);
        std::cout<<"RESUME reload=PASS optimizer=PASS counters=PASS nextStep=PASS"
                 <<" referenceHash="<<reference_next_hash<<" resumedHash="<<state_hash(resumed)
                 <<" loadPeakRss="<<peak_rss()<<"\n";
        return 0;
    }catch(const std::exception& error){std::cerr<<"FAIL "<<error.what()<<"\n";return 1;}
}
