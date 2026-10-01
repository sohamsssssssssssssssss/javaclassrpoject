#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace jade {

struct WindowRef { std::uint64_t token_byte_offset; };

/** Read-only, integrity-checked document-delimited token file; payload remains memory-mapped. */
class ProcessedPartition {
    struct Document { std::uint64_t first_byte, tokens; };
    int fd_=-1;
    const unsigned char* data_=nullptr;
    std::size_t bytes_=0;
    std::vector<Document> documents_;
    std::string dataset_digest_, tokenizer_digest_;
    int partition_=-1;
    std::uint64_t tokens_=0;
public:
    ProcessedPartition(const std::string& path,int expected_partition,
                       const std::string& tokenizer_digest,const std::string& dataset_digest);
    ~ProcessedPartition();
    ProcessedPartition(const ProcessedPartition&)=delete;
    ProcessedPartition& operator=(const ProcessedPartition&)=delete;
    std::uint64_t tokens() const { return tokens_; }
    std::size_t documents() const { return documents_.size(); }
    std::vector<WindowRef> windows(int context,int stride) const;
    std::array<int,33> window(WindowRef ref) const;
    std::uint64_t document_tokens(std::size_t index) const;
};

}
