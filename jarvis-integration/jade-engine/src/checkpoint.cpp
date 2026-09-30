#include "jade/engine.hpp"
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

namespace jade {
namespace {
std::array<std::uint32_t,256> crc_table(){
    std::array<std::uint32_t,256> table{};
    for(std::uint32_t i=0;i<256;i++){
        auto value=i;
        for(int bit=0;bit<8;bit++)value=(value>>1)^((value&1)?0xedb88320u:0u);
        table[i]=value;
    }
    return table;
}
std::uint32_t update_crc(std::uint32_t crc,const char* bytes,std::size_t count){
    static const auto table=crc_table();
    for(std::size_t i=0;i<count;i++)crc=(crc>>8)^table[(crc^std::uint8_t(bytes[i]))&255];
    return crc;
}
template<class T>void write(std::ofstream& out,std::uint32_t& crc,const T& value){
    const auto* bytes=reinterpret_cast<const char*>(&value);
    out.write(bytes,sizeof(T));crc=update_crc(crc,bytes,sizeof(T));
    if(!out)throw std::runtime_error("Checkpoint write failed");
}
void write_bytes(std::ofstream& out,std::uint32_t& crc,const char* bytes,std::size_t size){
    out.write(bytes,std::streamsize(size));crc=update_crc(crc,bytes,size);
    if(!out)throw std::runtime_error("Checkpoint write failed");
}
template<class T>T read(std::ifstream& in,std::uint32_t& crc){
    T value{};in.read(reinterpret_cast<char*>(&value),sizeof(T));
    if(!in)throw std::runtime_error("Truncated checkpoint");
    crc=update_crc(crc,reinterpret_cast<const char*>(&value),sizeof(T));return value;
}
void read_bytes(std::ifstream& in,std::uint32_t& crc,char* bytes,std::size_t size){
    in.read(bytes,std::streamsize(size));
    if(!in)throw std::runtime_error("Truncated checkpoint payload");
    crc=update_crc(crc,bytes,size);
}
}
void Engine::save(const std::string& path) const {
    const std::filesystem::path destination(path),temporary=path+".tmp."+std::to_string(getpid());
    if(!destination.has_parent_path()||!std::filesystem::exists(destination.parent_path()))
        throw std::invalid_argument("Checkpoint directory missing");
    try{
        std::ofstream out(temporary,std::ios::binary|std::ios::trunc);
        if(!out)throw std::runtime_error("Cannot create checkpoint temp file");
        std::uint32_t crc=0xffffffffu;
        write_bytes(out,crc,"JADEFP32",8);
        write(out,crc,std::uint32_t(1)); // format version
        write(out,crc,std::uint32_t(1)); // FP32 numeric type
        for(int value:{config_.vocab,config_.context,config_.width,config_.heads,config_.layers,config_.ffn})
            write(out,crc,std::uint32_t(value));
        write(out,crc,std::uint64_t(config_.parameter_count()));
        write(out,crc,steps_);write(out,crc,positions_);
        for(const Tensor* buffer:{&parameters_,&first_,&second_})
            write_bytes(out,crc,reinterpret_cast<const char*>(buffer->data()),buffer->size()*sizeof(float));
        const std::uint32_t checksum=crc^0xffffffffu;
        out.write(reinterpret_cast<const char*>(&checksum),sizeof(checksum));
        out.flush();if(!out)throw std::runtime_error("Checkpoint flush failed");
        out.close();if(!out)throw std::runtime_error("Checkpoint close failed");
        const int fd=open(temporary.c_str(),O_RDONLY);
        if(fd<0)throw std::runtime_error("Checkpoint fsync open failed");
        const int synced=fsync(fd);close(fd);
        if(synced!=0)throw std::runtime_error("Checkpoint fsync failed");
        std::filesystem::rename(temporary,destination);
    }catch(...){std::error_code ignored;std::filesystem::remove(temporary,ignored);throw;}
}
void Engine::load(const std::string& path){
    const auto count=config_.parameter_count();
    if(count>(std::numeric_limits<std::uint64_t>::max()-68)/12)
        throw std::overflow_error("Checkpoint size overflow");
    const std::uint64_t expected=68+std::uint64_t(count)*12;
    if(std::filesystem::file_size(path)!=expected)throw std::runtime_error("Checkpoint size mismatch");
    std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("Cannot open checkpoint");
    std::uint32_t crc=0xffffffffu;
    char magic[8];read_bytes(in,crc,magic,8);
    if(std::memcmp(magic,"JADEFP32",8)!=0||read<std::uint32_t>(in,crc)!=1||read<std::uint32_t>(in,crc)!=1)
        throw std::runtime_error("Unsupported checkpoint magic/version/type");
    const int config[]={config_.vocab,config_.context,config_.width,config_.heads,config_.layers,config_.ffn};
    for(int value:config)if(read<std::uint32_t>(in,crc)!=std::uint32_t(value))
        throw std::runtime_error("Checkpoint architecture mismatch");
    if(read<std::uint64_t>(in,crc)!=count)throw std::runtime_error("Checkpoint parameter count mismatch");
    const auto steps=read<std::uint64_t>(in,crc),positions=read<std::uint64_t>(in,crc);
    std::vector<float> parameters(count),first(count),second(count);
    for(auto* buffer:{&parameters,&first,&second})
        read_bytes(in,crc,reinterpret_cast<char*>(buffer->data()),count*sizeof(float));
    std::uint32_t saved=0;in.read(reinterpret_cast<char*>(&saved),sizeof(saved));
    if(!in||saved!=(crc^0xffffffffu))throw std::runtime_error("Checkpoint integrity mismatch");
    for(std::size_t i=0;i<count;i++){
        if(!std::isfinite(parameters[i])||!std::isfinite(first[i])||!std::isfinite(second[i])||second[i]<0)
            throw std::runtime_error("Checkpoint contains invalid numerical state");
    }
    std::copy(parameters.begin(),parameters.end(),parameters_.data());
    std::copy(first.begin(),first.end(),first_.data());
    std::copy(second.begin(),second.end(),second_.data());
    steps_=steps;positions_=positions;zero_grad();
}
} // namespace jade
