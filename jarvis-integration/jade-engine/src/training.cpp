#include "jade/training.hpp"
#include <CommonCrypto/CommonDigest.h>
#include <array>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>

namespace jade {
namespace {
std::string hex(const unsigned char* bytes,std::size_t size){
    static constexpr char digits[]="0123456789abcdef";std::string result;result.reserve(size*2);
    for(std::size_t i=0;i<size;i++){result+=digits[bytes[i]>>4];result+=digits[bytes[i]&15];}
    return result;
}
std::string sha256(const std::string& data){
    unsigned char digest[32];CC_SHA256(data.data(),CC_LONG(data.size()),digest);return hex(digest,32);
}
void atomic_text(const std::filesystem::path& path,const std::string& text){
    auto temp=path;temp+=".tmp."+std::to_string(getpid());
    try{
        std::ofstream out(temp,std::ios::binary|std::ios::trunc);
        out.write(text.data(),std::streamsize(text.size()));out.flush();
        if(!out)throw std::runtime_error("Metadata write failed");out.close();
        const int fd=open(temp.c_str(),O_RDONLY);
        if(fd<0)throw std::runtime_error("Metadata fsync open failed");
        const int result=fsync(fd);close(fd);
        if(result)throw std::runtime_error("Metadata fsync failed");
        std::filesystem::rename(temp,path);
    }catch(...){std::error_code ignored;std::filesystem::remove(temp,ignored);throw;}
}
std::string read_text(const std::filesystem::path& path){
    if(std::filesystem::file_size(path)>16384)throw std::runtime_error("Metadata too large");
    std::ifstream in(path,std::ios::binary);
    return std::string(std::istreambuf_iterator<char>{in},{});
}
}
double LearningRateSchedule::at(std::uint64_t step) const {
    if(!std::isfinite(peak)||!std::isfinite(minimum)||peak<=0||minimum<0||minimum>peak||
       !warmup||!total||warmup>=total||step<1)
        throw std::invalid_argument("Invalid learning-rate schedule");
    if(step<=warmup)return peak*double(step)/double(warmup);
    if(step>=total)return minimum;
    constexpr double pi=3.1415926535897932384626433832795;
    const double phase=double(step-warmup)/double(total-warmup);
    return minimum+(peak-minimum)*.5*(1+std::cos(pi*phase));
}
WarningPolicy WarningPolicy::from_environment(){
    auto value=[](const char* name,double fallback){
        const char* raw=std::getenv(name);if(!raw)return fallback;
        std::size_t used=0;double parsed=std::stod(raw,&used);
        if(used!=std::string(raw).size()||!std::isfinite(parsed)||parsed<=0)
            throw std::invalid_argument(std::string("Invalid warning threshold: ")+name);
        return parsed;
    };
    WarningPolicy result{value("JADE_WARN_LOSS_FACTOR",5),value("JADE_WARN_GRADIENT_NORM",100),
                         value("JADE_WARN_THROUGHPUT_FRACTION",.25)};
    if(result.throughput_fraction>=1)throw std::invalid_argument("Throughput warning fraction must be below 1");
    return result;
}
std::string PretrainingConfig::body() const {
    if(model.parameter_count()!=50483200||context!=32||stride<1||batch<1||batch>32||
       tokenizer_digest.size()!=64||dataset_digest.size()!=64||!max_steps||!wall_seconds||
       !checkpoint_every||!validation_every||!probe_every||!validation_windows||!retain_periodic||
       schedule.total!=max_steps)
        throw std::invalid_argument("Invalid JADE-50M pretraining configuration");
    schedule.at(1);
    std::ostringstream out;out<<std::setprecision(17);
    out<<"JADE-TRAIN-1\nmodel=1024,32,640,8,10,2560\nmodel_parameters=50483200\n"
       <<"tokenizer="<<tokenizer_digest<<"\ndataset="<<dataset_digest
       <<"\ncontext="<<context<<"\nstride="<<stride<<"\nbatch="<<batch
       <<"\noptimizer=AdamW\nlearning_rate_peak="<<schedule.peak
       <<"\nlearning_rate_min="<<schedule.minimum<<"\nwarmup_steps="<<schedule.warmup
       <<"\ntotal_steps="<<schedule.total<<"\nbeta1="<<optimizer.beta1
       <<"\nbeta2="<<optimizer.beta2<<"\nepsilon="<<optimizer.epsilon
       <<"\nweight_decay="<<optimizer.decay<<"\ngradient_clip="<<optimizer.clip
       <<"\nshuffle_seed="<<shuffle_seed<<"\ninitialization_seed="<<initialization_seed
       <<"\nmax_steps="<<max_steps<<"\ncheckpoint_every="<<checkpoint_every
       <<"\nvalidation_every="<<validation_every<<"\nprobe_every="<<probe_every
       <<"\nvalidation_windows="<<validation_windows<<"\nwall_seconds="<<wall_seconds
       <<"\nretain_periodic="<<retain_periodic<<"\n";
    return out.str();
}
std::string PretrainingConfig::digest() const {return sha256(body());}
void PretrainingConfig::save(const std::filesystem::path& path) const {
    const auto content=body();atomic_text(path,content+"sha256="+sha256(content)+"\n");
}
void PretrainingConfig::verify(const std::filesystem::path& path) const {
    const auto content=body();
    if(read_text(path)!=content+"sha256="+sha256(content)+"\n")
        throw std::runtime_error("Training configuration identity mismatch");
}
std::string sha256_file(const std::filesystem::path& path){
    std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("Cannot hash checkpoint");
    CC_SHA256_CTX state;CC_SHA256_Init(&state);std::array<char,1<<20> bytes{};
    while(in){in.read(bytes.data(),bytes.size());auto count=in.gcount();
        if(count>0)CC_SHA256_Update(&state,bytes.data(),CC_LONG(count));}
    if(!in.eof())throw std::runtime_error("Checkpoint hash read failed");
    unsigned char digest[32];CC_SHA256_Final(digest,&state);return hex(digest,32);
}
void save_pretraining_checkpoint(const Engine& model,const PretrainingConfig& config,
        const std::filesystem::path& path,double learning_rate){
    config.verify(path.parent_path()/"training-config.txt");
    model.save(path.string());
    std::ostringstream body;body<<std::setprecision(17)<<"JADE-TRAIN-CHECKPOINT-1\n"
        <<"config="<<config.digest()<<"\ntokenizer="<<config.tokenizer_digest
        <<"\ndataset="<<config.dataset_digest<<"\nmodel=1024,32,640,8,10,2560"
        <<"\ncheckpoint="<<sha256_file(path)<<"\nstep="<<model.steps()
        <<"\npositions="<<model.positions()<<"\nlearning_rate="<<learning_rate<<"\n";
    auto meta=path;meta+=".meta";
    atomic_text(meta,body.str()+"sha256="+sha256(body.str())+"\n");
}
void verify_pretraining_checkpoint(Engine& model,const PretrainingConfig& config,
        const std::filesystem::path& path){
    config.verify(path.parent_path()/"training-config.txt");
    auto meta=path;meta+=".meta";auto text=read_text(meta);
    const auto tail=text.rfind("sha256=");
    if(tail==std::string::npos||text.substr(tail+7)!=sha256(text.substr(0,tail))+"\n"||
       text.find("config="+config.digest()+"\n")==std::string::npos||
       text.find("tokenizer="+config.tokenizer_digest+"\n")==std::string::npos||
       text.find("dataset="+config.dataset_digest+"\n")==std::string::npos||
       text.find("model=1024,32,640,8,10,2560\n")==std::string::npos||
       text.find("checkpoint="+sha256_file(path)+"\n")==std::string::npos)
        throw std::runtime_error("Checkpoint metadata identity/integrity mismatch");
    model.load(path.string());
    if(text.find("step="+std::to_string(model.steps())+"\n")==std::string::npos||
       text.find("positions="+std::to_string(model.positions())+"\n")==std::string::npos)
        throw std::runtime_error("Checkpoint counters mismatch");
}
}
