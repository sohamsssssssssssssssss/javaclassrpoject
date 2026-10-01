#include "jade/dataset.hpp"
#include <CommonCrypto/CommonDigest.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <filesystem>
#include <limits>
#include <stdexcept>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

namespace jade {
namespace {
std::uint32_t u32(const unsigned char* p){return std::uint32_t(p[0])|(std::uint32_t(p[1])<<8)|
        (std::uint32_t(p[2])<<16)|(std::uint32_t(p[3])<<24);}
std::uint64_t u64(const unsigned char* p){return std::uint64_t(u32(p))|(std::uint64_t(u32(p+4))<<32);}
std::string hex(const unsigned char* p,std::size_t n){
    static constexpr char digits[]="0123456789abcdef";std::string value;value.reserve(2*n);
    for(std::size_t i=0;i<n;i++){value+=digits[p[i]>>4];value+=digits[p[i]&15];}
    return value;
}
}
ProcessedPartition::ProcessedPartition(const std::string& path,int expected_partition,
        const std::string& tokenizer_digest,const std::string& dataset_digest){
    if(expected_partition<0||expected_partition>2||tokenizer_digest.size()!=64||dataset_digest.size()!=64)
        throw std::invalid_argument("Invalid processed partition identity");
    auto length=std::filesystem::file_size(path);
    if(length<128||length>64ull*1024*1024*1024||length>std::numeric_limits<std::size_t>::max())
        throw std::runtime_error("Invalid processed token file length");
    fd_=open(path.c_str(),O_RDONLY);if(fd_<0)throw std::runtime_error("Cannot open processed tokens");
    bytes_=std::size_t(length);
    void* mapped=mmap(nullptr,bytes_,PROT_READ,MAP_PRIVATE,fd_,0);
    if(mapped==MAP_FAILED){close(fd_);fd_=-1;throw std::runtime_error("Cannot map processed tokens");}
    data_=static_cast<const unsigned char*>(mapped);
    try {
        if(std::memcmp(data_,"JDTOK001",8)||u32(data_+8)!=1||u32(data_+12)!=std::uint32_t(expected_partition))
            throw std::runtime_error("Processed token header mismatch");
        tokenizer_digest_=hex(data_+16,32);dataset_digest_=hex(data_+48,32);
        if(tokenizer_digest_!=tokenizer_digest||dataset_digest_!=dataset_digest)
            throw std::runtime_error("Processed token identity mismatch");
        const auto declared_docs=u64(data_+80),declared_tokens=u64(data_+88);
        if(declared_docs>1'000'000||declared_tokens>16'000'000'000ull)
            throw std::runtime_error("Processed token counts exceed engineering limits");
        unsigned char actual[CC_SHA256_DIGEST_LENGTH];
        CC_SHA256_CTX sha;CC_SHA256_Init(&sha);
        std::size_t remaining=bytes_-128;const unsigned char* cursor=data_+128;
        while(remaining){const auto n=std::min<std::size_t>(remaining,1<<20);
            CC_SHA256_Update(&sha,cursor,static_cast<CC_LONG>(n));cursor+=n;remaining-=n;}
        CC_SHA256_Final(actual,&sha);
        if(std::memcmp(actual,data_+96,32))throw std::runtime_error("Processed token payload digest mismatch");
        std::size_t offset=128;
        for(std::uint64_t i=0;i<declared_docs;i++){
            if(offset>bytes_-8)throw std::runtime_error("Truncated document header");
            auto count=u64(data_+offset);offset+=8;
            if(count>(bytes_-offset)/4)throw std::runtime_error("Truncated document tokens");
            documents_.push_back({offset,count});tokens_+=count;
            if(tokens_>declared_tokens)throw std::runtime_error("Token count mismatch");
            for(std::uint64_t j=0;j<count;j++)if(u32(data_+offset+4*j)>=1024)
                throw std::runtime_error("Token ID exceeds vocabulary");
            offset+=std::size_t(count)*4;
        }
        if(offset!=bytes_||tokens_!=declared_tokens)throw std::runtime_error("Processed token trailing/count mismatch");
        partition_=expected_partition;
    } catch (...) {munmap(const_cast<unsigned char*>(data_),bytes_);close(fd_);data_=nullptr;fd_=-1;throw;}
}
ProcessedPartition::~ProcessedPartition(){if(data_)munmap(const_cast<unsigned char*>(data_),bytes_);if(fd_>=0)close(fd_);}
std::vector<WindowRef> ProcessedPartition::windows(int context,int stride) const {
    if(context!=32||stride<1||stride>4096)throw std::invalid_argument("Invalid context or stride");
    std::uint64_t count=0;
    for(auto doc:documents_)if(doc.tokens>std::uint64_t(context)){
        count+=1+(doc.tokens-context-1)/stride;
        if(count>10'000'000)throw std::runtime_error("Window-index limit exceeded");
    }
    // ponytail: bounded in-memory references; external permutation when >10M windows are needed.
    std::vector<WindowRef> result;result.reserve(std::size_t(count));
    for(auto doc:documents_)for(std::uint64_t start=0;start+context<doc.tokens;start+=stride)
        result.push_back({doc.first_byte+start*4});
    return result;
}
std::array<int,33> ProcessedPartition::window(WindowRef ref) const {
    auto next=std::upper_bound(documents_.begin(),documents_.end(),ref.token_byte_offset,
                              [](std::uint64_t offset,const Document& doc){return offset<doc.first_byte;});
    if(next==documents_.begin())throw std::out_of_range("Window crosses a document boundary");
    const auto& doc=*--next;
    const auto delta=ref.token_byte_offset-doc.first_byte,start=delta/4;
    if(delta%4||start>doc.tokens||doc.tokens-start<33)
        throw std::out_of_range("Window crosses a document boundary");
    std::array<int,33> result{};
    for(std::size_t i=0;i<result.size();i++)result[i]=int(u32(data_+ref.token_byte_offset+i*4));
    return result;
}
std::uint64_t ProcessedPartition::document_tokens(std::size_t index) const {
    if(index>=documents_.size())throw std::out_of_range("Document index");return documents_[index].tokens;
}
}
