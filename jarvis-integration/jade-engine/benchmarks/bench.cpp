#include "jade/engine.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <sys/resource.h>
using namespace jade;
using Clock=std::chrono::steady_clock;
static double seconds(Clock::time_point start){return std::chrono::duration<double>(Clock::now()-start).count();}
static std::uint64_t rss(){struct rusage u{};getrusage(RUSAGE_SELF,&u);
#ifdef __APPLE__
    return u.ru_maxrss;
#else
    return std::uint64_t(u.ru_maxrss)*1024;
#endif
}
static double cpu(){struct rusage u{};getrusage(RUSAGE_SELF,&u);return u.ru_utime.tv_sec+u.ru_utime.tv_usec/1e6+u.ru_stime.tv_sec+u.ru_stime.tv_usec/1e6;}
static void load_weights(Engine& engine,const std::filesystem::path& path){
    const auto bytes=engine.config().parameter_count()*sizeof(float);
    if(std::filesystem::file_size(path)!=bytes)throw std::runtime_error("Random fixture size mismatch");
    std::ifstream in(path,std::ios::binary);in.read(reinterpret_cast<char*>(engine.weights()),std::streamsize(bytes));
    if(!in)throw std::runtime_error("Random fixture read failed");
    for(std::size_t i=0;i<engine.config().parameter_count();i++)if(!std::isfinite(engine.weights()[i]))throw std::runtime_error("Nonfinite random fixture");
}
static void stats(const char* name,std::vector<double> values){
    std::sort(values.begin(),values.end());
    std::cout<<name<<"_min="<<values.front()<<" "<<name<<"_median="<<values[values.size()/2]<<" "<<name<<"_max="<<values.back()<<" ";
}
int main(int argc,char** argv){
    try{
        if(argc!=5)throw std::invalid_argument("model backend fixture-dir checkpoint-dir");
        const std::string name=argv[1],backend_name=argv[2];
        Config c=name=="58K"?Config{512,32,32,4,2,128}:name=="250K"?Config{1024,32,64,4,2,256}:
                 name=="1M"?Config{1024,32,128,4,4,512}:name=="5M"?Config{1024,32,256,4,6,1024}:
                 throw std::invalid_argument("Unknown scale");
        Backend backend=backend_name=="naive"?Backend::Naive:backend_name=="accelerate"?Backend::Accelerate:
                        throw std::invalid_argument("Unknown backend");
        const std::filesystem::path fixtures(argv[3]),output(argv[4]);std::filesystem::create_directories(output);
        std::array<std::array<int,33>,2> sequences{};std::ifstream tokens(fixtures/("tokens-"+std::to_string(c.vocab)+".txt"));
        for(auto& sequence:sequences)for(int& id:sequence)if(!(tokens>>id)||id<0||id>=c.vocab)throw std::runtime_error("Invalid token fixture");
        Engine engine(c,backend);load_weights(engine,fixtures/("weights-"+name+".bin"));
        auto gradient=[&](Engine& model){model.zero_grad();float loss=0;for(const auto& seq:sequences)loss+=model.backward(seq.data(),seq.data()+1,32)/2;return loss;};
        auto loss=[&](Engine& model){float value=0;for(const auto& seq:sequences)value+=model.forward_loss(seq.data(),seq.data()+1,32)/2;return value;};
        for(int warm=0;warm<2;warm++){gradient(engine);engine.adamw({},2);}
        std::vector<double> forward,backward,optimizer,total,rates;
        const auto allocations=Tensor::allocations().allocations;const auto wall=Clock::now();const double cpu_start=cpu();
        for(int sample=0;sample<7;sample++){
            auto start=Clock::now();loss(engine);forward.push_back(seconds(start));
            start=Clock::now();gradient(engine);backward.push_back(seconds(start));
            start=Clock::now();engine.adamw({},2);optimizer.push_back(seconds(start));
            total.push_back(backward.back()+optimizer.back());rates.push_back(64/total.back());
        }
        const double wall_seconds=seconds(wall),cpu_seconds=cpu()-cpu_start;
        const auto training_rss=rss();
        const auto checkpoint=output/(name+"-"+backend_name+".jade");engine.save(checkpoint.string());
        std::cout<<std::setprecision(10)<<"BENCH model="<<name<<" backend="<<backend_name<<" parameters="<<c.parameter_count()<<" context=32 batch=2 samples=7 warmup=2 ";
        stats("forward",forward);stats("backwardIncludingForward",backward);stats("adamw",optimizer);stats("step",total);stats("positionsPerSecond",rates);
        std::cout<<"persistentBytes="<<engine.persistent_bytes()<<" workspaceBytes="<<engine.workspace_bytes()
                 <<" trainingPeakRss="<<training_rss<<" checkpointPeakRss="<<rss()<<" checkpointBytes="<<std::filesystem::file_size(checkpoint)
                 <<" hotTensorAllocations="<<Tensor::allocations().allocations-allocations
                 <<" tensorPeakBytes="<<Tensor::allocations().peak_bytes<<" cpuWallRatio="<<cpu_seconds/wall_seconds<<"\n";
        if(name=="5M"&&backend==Backend::Accelerate){
            Engine micro(c,backend);load_weights(micro,fixtures/("weights-"+name+".bin"));
            const float initial=loss(micro);const auto start=Clock::now();
            for(int step=0;step<3;step++){gradient(micro);micro.adamw({},2);}
            const auto saved=output/"5M-micro.jade";micro.save(saved.string());
            Engine resumed(c,backend);resumed.load(saved.string());
            gradient(micro);micro.adamw({},2);gradient(resumed);resumed.adamw({},2);
            const auto n=c.parameter_count();
            if(std::memcmp(micro.weights(),resumed.weights(),n*sizeof(float))||
               std::memcmp(micro.first_moments(),resumed.first_moments(),n*sizeof(float))||
               std::memcmp(micro.second_moments(),resumed.second_moments(),n*sizeof(float))||
               resumed.steps()!=4||resumed.positions()!=256)throw std::runtime_error("Micro resume mismatch");
            const float final_loss=loss(resumed);if(!(final_loss<initial))throw std::runtime_error("Micro loss failed to decrease");
            const double elapsed=seconds(start);
            std::cout<<"MICRO steps=4 positions=256 actualOptimizerCalls=5 initialLoss="<<initial<<" finalLoss="<<final_loss
                     <<" seconds="<<elapsed<<" positionsPerSecond="<<256/elapsed<<" peakRss="<<rss()
                     <<" checkpointBytes="<<std::filesystem::file_size(saved)<<" resume=PASS\n";
        }
        return 0;
    }catch(const std::exception& error){std::cerr<<"FAIL "<<error.what()<<"\n";return 1;}
}
