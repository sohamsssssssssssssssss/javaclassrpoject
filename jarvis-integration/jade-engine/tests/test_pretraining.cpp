#include "jade/dataset.hpp"
#include "jade/tokenizer.hpp"
#include "jade/training.hpp"
#include <CommonCrypto/CommonDigest.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <random>
#include <sstream>
#include <stdexcept>

using namespace jade;
namespace {
void check(bool value,const char* message){if(!value)throw std::runtime_error(message);}
void u32(std::vector<unsigned char>& out,std::uint32_t x){for(int i=0;i<4;i++)out.push_back((x>>(i*8))&255);}
void u64(std::vector<unsigned char>& out,std::uint64_t x){for(int i=0;i<8;i++)out.push_back((x>>(i*8))&255);}
std::string unescape(std::string text){
    std::string result;
    for(std::size_t i=0;i<text.size();i++){
        if(text[i]=='\\'&&i+1<text.size()){
            char c=text[++i];result+=c=='n'?'\n':c=='t'?'\t':c;
        }else result+=text[i];
    }
    return result;
}
void fixture(const std::filesystem::path& path,const std::string& tokenizer){
    std::vector<unsigned char> payload;
    for(int document=0;document<2;document++){
        u64(payload,40);
        for(int token=0;token<40;token++)u32(payload,document*100+token);
    }
    unsigned char hash[32];CC_SHA256(payload.data(),CC_LONG(payload.size()),hash);
    std::vector<unsigned char> header(128);std::copy_n("JDTOK001",8,header.begin());
    header[8]=1;
    for(std::size_t i=0;i<32;i++)header[16+i]=std::stoul(tokenizer.substr(i*2,2),nullptr,16);
    header[80]=2;header[88]=80;std::copy_n(hash,32,header.begin()+96);
    std::ofstream out(path,std::ios::binary);
    out.write(reinterpret_cast<const char*>(header.data()),std::streamsize(header.size()));
    out.write(reinterpret_cast<const char*>(payload.data()),std::streamsize(payload.size()));
}
}
int main(int argc,char** argv){
    try{
        check(argc==3,"artifact golden-tsv arguments");
        Tokenizer tokenizer;tokenizer.load_artifact(argv[1]);
        check(tokenizer.vocabulary_size()==1024&&tokenizer.artifact_digest().size()==64,"tokenizer artifact identity");
        std::ifstream golden(argv[2]);std::string line;std::getline(golden,line);int examples=0;
        while(std::getline(golden,line)){
            auto tab=line.find('\t');check(tab!=std::string::npos,"golden format");
            std::string input=unescape(line.substr(0,tab));std::istringstream ids(line.substr(tab+1));
            std::vector<int> expected;std::string piece;
            while(std::getline(ids,piece,','))expected.push_back(std::stoi(piece));
            check(tokenizer.encode(input)==expected&&tokenizer.decode(expected)==input,"Java/C++ tokenizer parity");examples++;
        }
        check(examples>=12,"golden coverage");
        setenv("JADE_WARN_THROUGHPUT_FRACTION","0.5",1);
        check(WarningPolicy::from_environment().throughput_fraction==.5,"configurable throughput warning");
        unsetenv("JADE_WARN_THROUGHPUT_FRACTION");
        PretrainingConfig config;config.tokenizer_digest=tokenizer.artifact_digest();
        config.dataset_digest=std::string(64,'0');config.max_steps=1000;config.schedule.total=1000;
        auto config_path=std::filesystem::temp_directory_path()/"jade-pretraining-config-test.txt";
        config.save(config_path);config.verify(config_path);
        config.dataset_digest=std::string(64,'1');
        bool incompatible=false;try{config.verify(config_path);}catch(const std::runtime_error&){incompatible=true;}
        check(incompatible,"resume config mismatch rejected");std::filesystem::remove(config_path);
        Engine tiny({32,4,8,2,1,16},Backend::Naive,12);
        const int input[]={1,2,3,4},target[]={2,3,4,5};
        auto before=tiny.weights()[0],moment=tiny.first_moments()[0];auto steps=tiny.steps();
        const auto count=tiny.config().parameter_count();
        std::vector<float> parameters(tiny.weights(),tiny.weights()+count),
                           gradients(tiny.gradients(),tiny.gradients()+count),
                           first(tiny.first_moments(),tiny.first_moments()+count),
                           second(tiny.second_moments(),tiny.second_moments()+count);
        tiny.forward_loss(input,target,4);
        check(tiny.weights()[0]==before&&tiny.first_moments()[0]==moment&&tiny.steps()==steps&&
              std::memcmp(parameters.data(),tiny.weights(),count*sizeof(float))==0&&
              std::memcmp(gradients.data(),tiny.gradients(),count*sizeof(float))==0&&
              std::memcmp(first.data(),tiny.first_moments(),count*sizeof(float))==0&&
              std::memcmp(second.data(),tiny.second_moments(),count*sizeof(float))==0,
              "validation is read-only");
        check(tiny.generate({1,2},2,.8f,4,26167)==tiny.generate({1,2},2,.8f,4,26167),
              "fixed-seed probe determinism");
        tiny.zero_grad();tiny.backward(input,target,4);
        tiny.gradient_data()[0]=std::numeric_limits<float>::infinity();
        bool diverged=false;try{tiny.adamw({},1);}catch(const std::runtime_error&){diverged=true;}
        check(diverged&&tiny.weights()[0]==before&&tiny.steps()==0,"nonfinite gradient abort before update");
        LearningRateSchedule schedule{.001,.0001,10,100};
        check(std::abs(schedule.at(1)-.0001)<1e-12&&std::abs(schedule.at(10)-.001)<1e-12&&
              schedule.at(50)<.001&&schedule.at(50)>.0001&&
              std::abs(schedule.at(100)-.0001)<1e-12&&schedule.at(101)==.0001,
              "warmup/cosine schedule");
        auto path=std::filesystem::temp_directory_path()/"jade-pretraining-test.jtok";
        fixture(path,tokenizer.artifact_digest());
        ProcessedPartition partition(path.string(),0,tokenizer.artifact_digest(),std::string(64,'0'));
        check(partition.documents()==2&&partition.tokens()==80,"processed header counts");
        auto refs=partition.windows(32,32);
        check(refs.size()==2&&partition.window(refs[0])[0]==0&&partition.window(refs[1])[0]==100,
              "whole-document windows");
        bool rejected=false;try{partition.window({refs[0].token_byte_offset+32*4});}
        catch(const std::out_of_range&){rejected=true;}check(rejected,"cross-document window rejected");
        auto a=refs,b=refs;std::mt19937_64 one(42),two(42);
        std::shuffle(a.begin(),a.end(),one);std::shuffle(b.begin(),b.end(),two);
        check(a[0].token_byte_offset==b[0].token_byte_offset&&
              a[1].token_byte_offset==b[1].token_byte_offset,"deterministic shuffle");
        {
            std::fstream out(path,std::ios::binary|std::ios::in|std::ios::out);
            out.seekp(-1,std::ios::end);out.put('\1');
        }
        rejected=false;try{ProcessedPartition broken(path.string(),0,tokenizer.artifact_digest(),std::string(64,'0'));}
        catch(const std::runtime_error&){rejected=true;}check(rejected,"payload corruption rejected");
        std::filesystem::remove(path);
        return 0;
    }catch(const std::exception& error){std::cerr<<"FAIL "<<error.what()<<'\n';return 1;}
}
