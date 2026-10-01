#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#ifdef __APPLE__
#include <CommonCrypto/CommonDigest.h>
#endif

namespace jade {

class Tokenizer {
    struct Merge {
        int left;
        int right;
        int result;
        int rank;
    };
    std::unordered_map<std::uint64_t, Merge> pairs_;
    std::vector<std::vector<uint8_t>> vocab_bytes_;
    std::string artifact_digest_, corpus_digest_;

    static std::uint64_t pair_key(int left, int right) {
        return (static_cast<std::uint64_t>(left) << 32) | (static_cast<std::uint64_t>(right) & 0xffffffffULL);
    }

public:
    Tokenizer() {
        vocab_bytes_.resize(256);
        for (int i = 0; i < 256; i++) {
            vocab_bytes_[i] = {static_cast<uint8_t>(i)};
        }
    }

    void load_merges(const std::string& path) {
        std::ifstream in(path);
        if (!in) throw std::runtime_error("Cannot open merges file: " + path);
        int left, right;
        int rank = 0;
        while (in >> left >> right) {
            int result = 256 + rank;
            Merge m{left, right, result, rank};
            pairs_[pair_key(left, right)] = m;

            std::vector<uint8_t> bytes = vocab_bytes_[left];
            bytes.insert(bytes.end(), vocab_bytes_[right].begin(), vocab_bytes_[right].end());
            vocab_bytes_.push_back(bytes);
            rank++;
        }
        if (vocab_bytes_.size() != 1024) {
            throw std::runtime_error("Expected 1024 vocabulary size, got " + std::to_string(vocab_bytes_.size()));
        }
    }

    void load_artifact(const std::string& path) {
#ifndef __APPLE__
        throw std::runtime_error("JADE tokenizer SHA-256 loader requires a platform digest implementation");
#else
        if (std::filesystem::file_size(path) > 131072) throw std::runtime_error("Tokenizer artifact too large");
        std::ifstream in(path, std::ios::binary);
        if (!in) throw std::runtime_error("Cannot open tokenizer artifact");
        const std::string content(std::istreambuf_iterator<char>{in}, {});
        const auto suffix = content.rfind("sha256=");
        if (suffix == std::string::npos || content.empty() || content.back() != '\n')
            throw std::runtime_error("Missing tokenizer digest");
        const std::string body = content.substr(0, suffix);
        const std::string expected = content.substr(suffix + 7, content.size() - suffix - 8);
        unsigned char hash[CC_SHA256_DIGEST_LENGTH];
        CC_SHA256(body.data(), static_cast<CC_LONG>(body.size()), hash);
        std::ostringstream hex;
        for (unsigned char c : hash) hex << std::hex << std::setw(2) << std::setfill('0') << int(c);
        if (expected != hex.str()) throw std::runtime_error("Tokenizer artifact integrity mismatch");
        std::istringstream lines(body);
        std::string magic, vocab_line, corpus_line, merges_line;
        if (!std::getline(lines, magic) || magic != "JADE-BPE-1" ||
            !std::getline(lines, vocab_line) || vocab_line != "vocab=1024" ||
            !std::getline(lines, corpus_line) || corpus_line.rfind("corpus=", 0) != 0 ||
            !std::getline(lines, merges_line) || merges_line != "merges=768")
            throw std::runtime_error("Invalid tokenizer artifact header");
        const std::string corpus = corpus_line.substr(7);
        if (corpus.size() != 64 || corpus.find_first_not_of("0123456789abcdef") != std::string::npos)
            throw std::runtime_error("Invalid tokenizer corpus identity");
        std::vector<std::vector<uint8_t>> vocabulary(256);
        std::set<std::string> unique;
        for (int i = 0; i < 256; i++) { vocabulary[i] = {static_cast<uint8_t>(i)}; unique.insert(std::string(1, char(i))); }
        std::unordered_map<std::uint64_t, Merge> pairs;
        std::string line;
        for (int rank = 0; rank < 768; rank++) {
            if (!std::getline(lines, line)) throw std::runtime_error("Truncated tokenizer merges");
            std::istringstream parts(line); int left, right, result; std::string extra;
            if (!(parts >> left >> right >> result) || (parts >> extra) ||
                left < 0 || right < 0 || left >= int(vocabulary.size()) || right >= int(vocabulary.size()) ||
                result != int(vocabulary.size()) || pairs.count(pair_key(left, right)))
                throw std::runtime_error("Invalid tokenizer merge rule");
            auto bytes = vocabulary[left];
            bytes.insert(bytes.end(), vocabulary[right].begin(), vocabulary[right].end());
            if (bytes.size() > 4096 || !unique.insert(std::string(reinterpret_cast<const char*>(bytes.data()), bytes.size())).second)
                throw std::runtime_error("Duplicate or oversized tokenizer token");
            vocabulary.push_back(std::move(bytes));
            pairs.emplace(pair_key(left, right), Merge{left, right, result, rank});
        }
        if (std::getline(lines, line)) throw std::runtime_error("Trailing tokenizer data");
        vocab_bytes_ = std::move(vocabulary);
        pairs_ = std::move(pairs);
        artifact_digest_ = expected;
        corpus_digest_ = corpus;
#endif
    }

    const std::string& artifact_digest() const { return artifact_digest_; }
    const std::string& corpus_digest() const { return corpus_digest_; }

    std::vector<int> encode(const std::string& text) const {
        std::vector<int> ids;
        ids.reserve(text.size());
        for (unsigned char c : text) {
            ids.push_back(static_cast<int>(c));
        }

        while (ids.size() > 1 && !pairs_.empty()) {
            const Merge* selected = nullptr;
            for (size_t i = 0; i + 1 < ids.size(); i++) {
                auto it = pairs_.find(pair_key(ids[i], ids[i + 1]));
                if (it != pairs_.end()) {
                    if (!selected || it->second.rank < selected->rank) {
                        selected = &it->second;
                    }
                }
            }
            if (!selected) break;

            std::vector<int> output;
            output.reserve(ids.size());
            for (size_t i = 0; i < ids.size(); i++) {
                if (i + 1 < ids.size() && ids[i] == selected->left && ids[i + 1] == selected->right) {
                    output.push_back(selected->result);
                    i++;
                } else {
                    output.push_back(ids[i]);
                }
            }
            ids = std::move(output);
        }
        return ids;
    }

    std::string decode(const std::vector<int>& ids) const {
        std::string result;
        for (int id : ids) {
            if (id >= 0 && static_cast<size_t>(id) < vocab_bytes_.size()) {
                const auto& bytes = vocab_bytes_[id];
                result.append(reinterpret_cast<const char*>(bytes.data()), bytes.size());
            } else {
                result += "<unk>";
            }
        }
        return result;
    }

    std::string decode_token(int id) const {
        if (id >= 0 && static_cast<size_t>(id) < vocab_bytes_.size()) {
            const auto& bytes = vocab_bytes_[id];
            return std::string(reinterpret_cast<const char*>(bytes.data()), bytes.size());
        }
        return "<unk>";
    }

    size_t vocabulary_size() const { return vocab_bytes_.size(); }
};

} // namespace jade
