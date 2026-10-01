#include "jade/dataset.hpp"
#include "jade/engine.hpp"
#include "jade/tokenizer.hpp"
#include "jade/training.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <random>
#include <sstream>
#include <stdexcept>
#include <sys/resource.h>

using namespace jade;
using Clock=std::chrono::steady_clock;
namespace {
constexpr Config shape{1024,32,640,8,10,2560};
const std::vector<std::string> probes={"hello","hello jade","my name is","what is","the","because",
    "once upon a time","the computer","User: hello\nAssistant:","User: what is a computer?\nAssistant:"};
const std::vector<std::string> chat_baseline={"hello","hello jade","my name is","what is a computer",
    "what is java","the sky is","once upon a time","User: hello\nAssistant:",
    "User: what is a computer?\nAssistant:","User: who are you?\nAssistant:"};
std::uint64_t rss(){rusage usage{};getrusage(RUSAGE_SELF,&usage);
#ifdef __APPLE__
    return usage.ru_maxrss;
#else
    return std::uint64_t(usage.ru_maxrss)*1024;
#endif
}
double elapsed(Clock::time_point since){return std::chrono::duration<double>(Clock::now()-since).count();}
std::string escape(const std::string& text){
    std::string result;for(unsigned char c:text){
        if(c=='\n')result+="\\n";else if(c=='\r')result+="\\r";
        else if(c=='\t')result+="\\t";else if(c=='\\')result+="\\\\";
        else if(c<32||c==127){char digits[]="0123456789abcdef";result+="\\x";result+=digits[c>>4];result+=digits[c&15];}
        else result+=char(c);
    }return result;
}
std::string field(const std::filesystem::path& path,const std::string& key){
    std::ifstream in(path);std::string line;
    while(std::getline(in,line))if(line.rfind(key+"=",0)==0)return line.substr(key.size()+1);
    throw std::runtime_error("Missing processed identity field: "+key);
}
double evaluate(Engine& model,const ProcessedPartition& partition,const std::vector<WindowRef>& refs,
                std::size_t limit,std::uint64_t& positions){
    const auto before_steps=model.steps(),before_positions=model.positions();
    if(refs.empty()||!limit)throw std::runtime_error("Empty validation window set");
    double sum=0;auto count=std::min(limit,refs.size());
    for(std::size_t i=0;i<count;i++){
        auto data=partition.window(refs[i]);sum+=model.forward_loss(data.data(),data.data()+1,32);
    }
    if(model.steps()!=before_steps||model.positions()!=before_positions)
        throw std::runtime_error("Validation mutated training counters");
    positions=count*32;double loss=sum/count;
    if(!std::isfinite(loss))throw std::runtime_error("Nonfinite validation loss");
    return loss;
}
std::vector<int> continuation(Engine& model,const Tokenizer& tokenizer,const std::string& prompt,bool sample){
    auto input=tokenizer.encode(prompt);
    if(input.empty())throw std::runtime_error("Empty probe prompt");
    if(input.size()>=32)input.resize(31);
    const int count=std::min<int>(24,32-int(input.size()));
    return sample?model.generate(input,count,.8f,40,26167):model.generate(input,count);
}
void write_probes(std::ofstream& out,Engine& model,const Tokenizer& tokenizer,
                  std::uint64_t step,double train_loss,double val_loss,
                  const std::vector<std::string>& prompts){
    for(const auto& prompt:prompts)for(int mode=0;mode<2;mode++){
        auto ids=continuation(model,tokenizer,prompt,mode==1);
        out<<step<<'\t'<<std::setprecision(9)<<train_loss<<'\t'<<val_loss<<'\t'
           <<(mode?"sampled":"greedy")<<'\t'<<escape(prompt)<<'\t'
           <<escape(tokenizer.decode(ids))<<'\n';
    }
    out.flush();if(!out)throw std::runtime_error("Probe log write failed");
}
void finite_state(const Engine& model){
    const auto count=model.config().parameter_count();
    for(std::size_t i=0;i<count;i++)if(!std::isfinite(model.weights()[i])||
            !std::isfinite(model.first_moments()[i])||!std::isfinite(model.second_moments()[i]))
        throw std::runtime_error("Nonfinite persistent training state");
}
void log_row(std::ofstream& out,const Engine& model,std::uint64_t step,std::size_t windows,
             double lr,double train_loss,double val_loss,double norm,double clip,
             double positions_per_sec,double elapsed_seconds){
    out<<step<<'\t'<<std::setprecision(10)<<double(windows)<<'\t'<<lr<<'\t'<<train_loss<<'\t';
    if(std::isfinite(val_loss))out<<val_loss;out<<'\t'<<std::exp(train_loss)<<'\t';
    if(std::isfinite(val_loss))out<<std::exp(val_loss);
    out<<'\t'<<norm<<'\t'<<clip<<'\t'<<model.positions()<<'\t'
       <<positions_per_sec<<'\t'<<elapsed_seconds<<'\t'<<rss()<<'\n';
    out.flush();if(!out)throw std::runtime_error("Telemetry write failed");
}
void retain(std::deque<std::filesystem::path>& periodic,std::uint64_t keep){
    while(periodic.size()>keep){
        auto old=periodic.front();periodic.pop_front();
        std::filesystem::remove(old);auto meta=old;meta+=".meta";std::filesystem::remove(meta);
    }
}
}
int main(int argc,char** argv){
    try{
        if(argc!=5||std::string(argv[1])!="pilot")
            throw std::invalid_argument("pilot processed-directory tokenizer-artifact NEW-run-directory");
        const std::filesystem::path processed(argv[2]),artifact(argv[3]),run(argv[4]);
        const WarningPolicy warnings=WarningPolicy::from_environment();
        if(std::filesystem::exists(run))throw std::runtime_error("Fresh run directory already exists");
        Tokenizer tokenizer;tokenizer.load_artifact(artifact.string());
        const auto dataset_digest=field(processed/"identity.txt","dataset");
        if(field(processed/"identity.txt","tokenizer")!=tokenizer.artifact_digest())
            throw std::runtime_error("Tokenizer/dataset identity mismatch");
        ProcessedPartition train((processed/"train.jtok").string(),0,tokenizer.artifact_digest(),dataset_digest);
        ProcessedPartition validation((processed/"validation.jtok").string(),1,tokenizer.artifact_digest(),dataset_digest);
        ProcessedPartition test((processed/"test.jtok").string(),2,tokenizer.artifact_digest(),dataset_digest);
        auto train_refs=train.windows(32,32),val_refs=validation.windows(32,32),test_refs=test.windows(32,32);
        if(train_refs.empty()||val_refs.empty()||test_refs.empty())throw std::runtime_error("Empty partition window set");
        const auto train_eval_refs=train_refs;
        const std::uint64_t steps=std::min<std::uint64_t>(3000,(train_refs.size()+1)/2);
        PretrainingConfig config;config.tokenizer_digest=tokenizer.artifact_digest();
        config.dataset_digest=dataset_digest;config.max_steps=steps;config.schedule.total=steps;
        config.schedule.warmup=std::min<std::uint64_t>(100,std::max<std::uint64_t>(1,steps/10));
        if(config.schedule.warmup>=steps)throw std::runtime_error("Pilot dataset too small for schedule");
        const auto plan=plan_memory(shape);
        std::filesystem::create_directories(run.parent_path());
        if(std::filesystem::space(run.parent_path()).available<4*plan.checkpoint_bytes+256ull*1024*1024)
            throw std::runtime_error("Insufficient disk for pilot checkpoint cadence");
        std::filesystem::create_directories(run);
        config.save(run/"training-config.txt");
        {
            std::ofstream policy(run/"warning-policy.txt");
            policy<<"loss_multiplier="<<warnings.loss_multiplier<<"\ngradient_norm="<<warnings.gradient_norm
                  <<"\nthroughput_fraction="<<warnings.throughput_fraction<<"\n";
        }
        std::ofstream telemetry(run/"telemetry.tsv"),probe_log(run/"generation-probes.tsv");
        telemetry<<"step\tepoch_progress\tlearning_rate\ttrain_loss\tvalidation_loss\ttrain_ppl\tvalidation_ppl\tgradient_norm\tclip_factor\tpositions\tpositions_per_sec\telapsed_seconds\tpeak_rss_bytes\n";
        probe_log<<"step\ttrain_loss\tvalidation_loss\tmode\tprompt\tcontinuation\n";
        std::vector<std::string> expected_final;
        double initial_train=0,initial_val=0,final_train=0,final_val=0;
        double first_norm=0,last_norm=0,optimizer_seconds=0;
        std::uint64_t clipping_events=0,completed=0,validation_positions=0;
        const auto wall=Clock::now();
        {
            Engine model(shape,Backend::Accelerate,config.initialization_seed);
            std::uint64_t sample_positions=0;
            initial_train=evaluate(model,train,train_refs,std::min<std::size_t>(128,train_refs.size()),sample_positions);
            initial_val=evaluate(model,validation,val_refs,val_refs.size(),validation_positions);
            log_row(telemetry,model,0,0,0,initial_train,initial_val,0,1,0,elapsed(wall));
            write_probes(probe_log,model,tokenizer,0,initial_train,initial_val,probes);
            std::mt19937_64 rng(config.shuffle_seed);std::shuffle(train_refs.begin(),train_refs.end(),rng);
            std::deque<std::filesystem::path> periodic;
            double rolling=0,last_validation=initial_val;int in_rolling=0,explosion=0;
            double baseline_rate=0,last_log_time=elapsed(wall);std::uint64_t last_log_positions=0;
            for(std::size_t cursor=0;cursor<train_refs.size()&&completed<steps;){
                if(elapsed(wall)>=580){std::cerr<<"WARN wall-time reserve reached; ending pilot before next step\n";break;}
                int examples=int(std::min<std::size_t>(2,train_refs.size()-cursor));
                model.zero_grad();double batch_loss=0;
                const auto step_start=Clock::now();
                for(int i=0;i<examples;i++){
                    auto data=train.window(train_refs[cursor++]);
                    batch_loss+=model.backward(data.data(),data.data()+1,32)/examples;
                }
                if(!std::isfinite(batch_loss))throw std::runtime_error("Nonfinite training loss");
                auto settings=config.optimizer;settings.learning_rate=float(config.schedule.at(model.steps()+1));
                model.adamw(settings,examples);
                optimizer_seconds+=elapsed(step_start);completed=model.steps();
                const double norm=model.last_gradient_norm(),clip=model.last_clip_factor();
                if(completed==1)first_norm=norm;last_norm=norm;
                if(!std::isfinite(norm)||!std::isfinite(clip))throw std::runtime_error("Nonfinite gradient telemetry");
                if(clip<1)clipping_events++;
                rolling+=batch_loss;in_rolling++;
                if(batch_loss>warnings.loss_multiplier*initial_train)explosion++;else explosion=0;
                if(explosion>=10)std::cerr<<"WARN sustained high training loss at step "<<completed<<'\n';
                if(norm>warnings.gradient_norm)std::cerr<<"WARN extreme gradient norm at step "<<completed<<'\n';
                bool validate=completed%config.validation_every==0;
                if(validate)last_validation=evaluate(model,validation,val_refs,
                    std::min<std::size_t>(config.validation_windows,val_refs.size()),validation_positions);
                if(completed%50==0||completed==steps){
                    const double now=elapsed(wall);
                    const double interval_rate=(model.positions()-last_log_positions)/std::max(now-last_log_time,1e-9);
                    if(completed==50)baseline_rate=interval_rate;
                    if(completed>=150&&interval_rate<baseline_rate*warnings.throughput_fraction)
                        std::cerr<<"WARN throughput collapse at step "<<completed<<" interval_pos_per_sec="<<interval_rate<<'\n';
                    last_log_time=now;last_log_positions=model.positions();
                    const double train_loss=rolling/in_rolling;rolling=0;in_rolling=0;
                    finite_state(model);
                    log_row(telemetry,model,completed,cursor,settings.learning_rate,train_loss,
                            validate?last_validation:std::numeric_limits<double>::quiet_NaN(),norm,clip,
                            model.positions()/std::max(optimizer_seconds,1e-9),elapsed(wall));
                    std::cout<<"PROGRESS step="<<completed<<" positions="<<model.positions()
                             <<" loss="<<train_loss<<" validation="<<(validate?last_validation:-1)
                             <<" lr="<<settings.learning_rate<<" elapsed="<<elapsed(wall)<<'\n'<<std::flush;
                }
                if(completed%config.probe_every==0){
                    if(!validate)last_validation=evaluate(model,validation,val_refs,
                        std::min<std::size_t>(config.validation_windows,val_refs.size()),validation_positions);
                    write_probes(probe_log,model,tokenizer,completed,batch_loss,last_validation,probes);
                }
                if(completed%config.checkpoint_every==0){
                    auto checkpoint=run/("periodic-step"+std::to_string(completed)+".jade");
                    save_pretraining_checkpoint(model,config,checkpoint,settings.learning_rate);
                    periodic.push_back(checkpoint);retain(periodic,config.retain_periodic);
                }
            }
            std::uint64_t positions=0;
            final_train=evaluate(model,train,train_eval_refs,std::min<std::size_t>(128,train_eval_refs.size()),positions);
            final_val=evaluate(model,validation,val_refs,val_refs.size(),validation_positions);
            finite_state(model);
            log_row(telemetry,model,completed,train_refs.size(),
                    config.schedule.at(std::max<std::uint64_t>(1,completed)),final_train,final_val,
                    last_norm,model.last_clip_factor(),model.positions()/std::max(optimizer_seconds,1e-9),elapsed(wall));
            write_probes(probe_log,model,tokenizer,completed,final_train,final_val,probes);
            std::ofstream comparison(run/"chat-baseline-after.tsv");
            comparison<<"prompt\tmode\tcontinuation\n";
            for(const auto& prompt:chat_baseline)for(int mode=0;mode<2;mode++){
                auto ids=continuation(model,tokenizer,prompt,mode==1);
                comparison<<escape(prompt)<<'\t'<<(mode?"sampled":"greedy")<<'\t'
                          <<escape(tokenizer.decode(ids))<<'\n';
                if(!mode)expected_final.push_back(tokenizer.decode(ids));
            }
            comparison.flush();if(!comparison)throw std::runtime_error("Comparison output failed");
            save_pretraining_checkpoint(model,config,run/"final.jade",
                                        config.schedule.at(std::max<std::uint64_t>(1,completed)));
        }
        Engine resumed(shape,Backend::Accelerate,999);
        verify_pretraining_checkpoint(resumed,config,run/"final.jade");
        if(resumed.steps()!=completed||resumed.positions()==0)throw std::runtime_error("Resume counters mismatch");
        for(std::size_t i=0;i<chat_baseline.size();i++)if(tokenizer.decode(
                continuation(resumed,tokenizer,chat_baseline[i],false))!=expected_final[i])
            throw std::runtime_error("Resumed generation mismatch");
        std::ofstream summary(run/"summary.txt");
        summary<<std::setprecision(12)<<"steps="<<completed<<"\npositions="<<resumed.positions()
               <<"\nwall_seconds="<<elapsed(wall)<<"\noptimizer_seconds="<<optimizer_seconds
               <<"\npositions_per_optimizer_second="<<resumed.positions()/optimizer_seconds
               <<"\ninitial_train="<<initial_train<<"\nfinal_train="<<final_train
               <<"\ninitial_validation="<<initial_val<<"\nfinal_validation="<<final_val
               <<"\nvalidation_positions="<<validation_positions<<"\ninitial_gradient_norm="<<first_norm
               <<"\nfinal_gradient_norm="<<last_norm<<"\nclipping_events="<<clipping_events
               <<"\npeak_rss_bytes="<<rss()<<"\ncheckpoint_bytes="
               <<std::filesystem::file_size(run/"final.jade")<<"\nresume=PASS\n";
        summary.flush();if(!summary)throw std::runtime_error("Pilot summary write failed");
        std::cout<<"PILOT_PASS steps="<<completed<<" positions="<<resumed.positions()
                 <<" wall="<<elapsed(wall)<<" optimizer_pos_per_sec="<<resumed.positions()/optimizer_seconds
                 <<" initial_val="<<initial_val<<" final_val="<<final_val<<" peak_rss="<<rss()<<'\n';
        return 0;
    }catch(const std::exception& error){std::cerr<<"PILOT_FAIL "<<error.what()<<'\n';return 1;}
}
