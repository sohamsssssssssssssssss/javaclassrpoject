#pragma once

#include <cassert>
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace jade {

struct AllocationStats { std::size_t current_bytes, peak_bytes, allocations; };
class Tensor {
    std::size_t rows_=0, columns_=0;
    std::vector<float> values_;
    static AllocationStats stats_;
public:
    Tensor()=default;
    Tensor(std::size_t rows, std::size_t columns);
    ~Tensor();
    Tensor(const Tensor&)=delete;
    Tensor& operator=(const Tensor&)=delete;
    Tensor(Tensor&& other) noexcept;
    Tensor& operator=(Tensor&& other) noexcept;
    float* data() { return values_.data(); }
    const float* data() const { return values_.data(); }
    std::size_t rows() const { return rows_; }
    std::size_t columns() const { return columns_; }
    std::size_t size() const { return values_.size(); }
    void fill(float value);
    float& at(std::size_t row,std::size_t column) { assert(row<rows_&&column<columns_);return values_[row*columns_+column]; }
    float at(std::size_t row,std::size_t column) const { assert(row<rows_&&column<columns_);return values_[row*columns_+column]; }
    static AllocationStats allocations() { return stats_; }
};

struct EngineeringLimits {
    std::uint64_t max_parameters=60'000'000;
    std::uint64_t max_peak_bytes=2ull*1024*1024*1024;
};
struct Config {
    int vocab, context, width, heads, layers, ffn;
    std::size_t parameter_count(const EngineeringLimits& limits={}) const;
    void validate(const EngineeringLimits& limits={}) const;
};
struct MemoryPlan {
    std::uint64_t parameter_bytes,gradient_bytes,first_moment_bytes,second_moment_bytes;
    std::uint64_t persistent_bytes,workspace_bytes,checkpoint_bytes;
    std::uint64_t minimum_training_bytes,minimum_load_bytes,engineering_peak_bytes;
};
MemoryPlan plan_memory(Config config,const EngineeringLimits& limits={});
struct ConstructionRss {
    std::uint64_t before_model=0,after_parameters=0,after_optimizer=0,after_workspace=0;
};
enum class Backend { Naive, Accelerate };
void gemm(Backend backend, bool transpose_a, bool transpose_b, int m,int n,int k,
          const float* a,const float* b,float* out,float alpha=1,float beta=0);
void rmsnorm(const float* x,float* out,int rows,int width,float epsilon=1e-6f);
void rmsnorm_backward(const float* x,const float* upstream,float* out,int rows,int width,float epsilon=1e-6f);
float gelu(float x);
float gelu_derivative(float x);
void softmax(const float* input,float* output,int size);

struct Parameter { std::string name;int rows,columns;std::size_t offset; };
struct AdamWConfig { float learning_rate=.001f,beta1=.9f,beta2=.999f,epsilon=1e-8f,decay=.01f,clip=1.f; };
struct Trace { std::string name;const float* values;std::size_t size; };
struct ProfileTimes {
    std::uint64_t forward_ns=0,loss_ns=0,backward_ns=0,grad_accum_ns=0;
    std::uint64_t grad_norm_ns=0,clip_ns=0,adamw_update_ns=0,zero_ns=0;
};

class Engine {
    struct Layer {
        Tensor norm1,q,k,v,scores,probabilities,joined,projection,residual,norm2,pre_gelu,gelu_value,ffn_output;
        Layer(int context,int width,int heads,int ffn);
    };
    Config config_;
    Backend backend_;
    std::vector<Parameter> layout_;
    std::vector<std::array<std::size_t,6>> block_offsets_;
    std::size_t embedding_offset_=0,position_offset_=0,head_offset_=0;
    Tensor parameters_,gradients_,first_,second_;
    std::vector<Tensor> states_;
    std::vector<Layer> cache_;
    Tensor final_norm_,logits_,d_logits_,d_state_,d_next_,d_residual_,d_joined_,d_q_,d_k_,d_v_,d_norm_,d_ffn_,d_pre_,d_hidden_,d_probability_;
    std::uint64_t steps_=0,positions_=0;
    int last_length_=0;
    bool profiling_=false;
    ProfileTimes profile_{};
    double last_gradient_norm_=0,last_clip_factor_=1;
    void forward_hidden(const int* input,int length);
public:
    Engine(Config config,Backend backend,std::uint64_t seed=26167,
           EngineeringLimits limits={},ConstructionRss* construction_rss=nullptr);
    const Config& config() const { return config_; }
    Backend backend() const { return backend_; }
    const std::vector<Parameter>& layout() const { return layout_; }
    const Parameter& parameter(const std::string& name) const;
    float* weights() { return parameters_.data(); }
    const float* weights() const { return parameters_.data(); }
    const float* gradients() const { return gradients_.data(); }
    float* gradient_data() { return gradients_.data(); }
    const float* first_moments() const { return first_.data(); }
    const float* second_moments() const { return second_.data(); }
    std::size_t persistent_bytes() const { return 4*config_.parameter_count()*sizeof(float); }
    std::size_t workspace_bytes() const;
    std::uint64_t steps() const { return steps_; }
    std::uint64_t positions() const { return positions_; }
    void profile_enabled(bool enabled) { profiling_=enabled;profile_={}; }
    const ProfileTimes& profile() const { return profile_; }
    double last_gradient_norm() const { return last_gradient_norm_; }
    double last_clip_factor() const { return last_clip_factor_; }
    void zero_grad();
    float forward_loss(const int* input,const int* target,int length);
    float backward(const int* input,const int* target,int length); // Adds mean-loss gradients.
    void adamw(const AdamWConfig& settings,int examples=1);
    std::vector<int> generate(const std::vector<int>& prompt,int new_tokens);
    std::vector<int> generate(const std::vector<int>& prompt,int new_tokens,float temperature,int top_k,std::uint64_t seed);
    std::vector<Trace> trace(int layer,int length) const;
    void save(const std::string& path) const;
    void load(const std::string& path);
    void set_counters(std::uint64_t steps,std::uint64_t positions) { steps_=steps;positions_=positions; }
};

} // namespace jade
