#pragma once

#include "jade/engine.hpp"
#include <cstdint>
#include <filesystem>
#include <string>

namespace jade {

struct LearningRateSchedule {
    double peak=1e-4,minimum=1e-5;
    std::uint64_t warmup=100,total=1000;
    double at(std::uint64_t optimizer_step) const;
};

/** Warning thresholds affect reporting only; they never alter an optimizer step. */
struct WarningPolicy {
    double loss_multiplier=5,gradient_norm=100,throughput_fraction=.25;
    static WarningPolicy from_environment();
};

struct PretrainingConfig {
    Config model{1024,32,640,8,10,2560};
    std::string tokenizer_digest,dataset_digest;
    int context=32,stride=32,batch=2;
    std::uint64_t initialization_seed=26167,shuffle_seed=42,max_steps=0;
    std::uint64_t checkpoint_every=400,validation_every=200,probe_every=400;
    std::uint64_t validation_windows=128,wall_seconds=600,retain_periodic=2;
    LearningRateSchedule schedule{};
    AdamWConfig optimizer{};
    std::string body() const;
    std::string digest() const;
    void save(const std::filesystem::path& path) const;
    void verify(const std::filesystem::path& path) const;
};

std::string sha256_file(const std::filesystem::path& path);
void save_pretraining_checkpoint(const Engine& model,const PretrainingConfig& config,
                                 const std::filesystem::path& path,double learning_rate);
void verify_pretraining_checkpoint(Engine& model,const PretrainingConfig& config,
                                   const std::filesystem::path& path);
}
