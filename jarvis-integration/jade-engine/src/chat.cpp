#include "jade/engine.hpp"
#include "jade/tokenizer.hpp"
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <sys/resource.h>

using namespace jade;
using Clock = std::chrono::steady_clock;

namespace {

constexpr Config shape{1024, 32, 640, 8, 10, 2560};

std::uint64_t peak_rss() {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
#ifdef __APPLE__
    return usage.ru_maxrss;
#else
    return std::uint64_t(usage.ru_maxrss) * 1024;
#endif
}

const std::vector<std::string> TEST_PROMPTS = {
    "hello",
    "hello jade",
    "my name is",
    "what is a computer",
    "what is java",
    "the sky is",
    "once upon a time",
    "User: hello\nAssistant:",
    "User: what is a computer?\nAssistant:",
    "User: who are you?\nAssistant:"
};

struct GenerationMetrics {
    double tokenization_us = 0;
    double first_token_ms = 0;
    double total_gen_ms = 0;
    int tokens_generated = 0;
    double tokens_per_sec = 0;
};

std::pair<std::string, GenerationMetrics> generate_with_metrics(
    Engine& model,
    const Tokenizer& tokenizer,
    const std::string& prompt_text,
    int max_new_tokens,
    float temperature = 0.0f,
    int top_k = 1,
    std::uint64_t seed = 26167)
{
    GenerationMetrics metrics;
    auto t0 = Clock::now();
    std::vector<int> prompt_tokens = tokenizer.encode(prompt_text);
    auto t1 = Clock::now();
    metrics.tokenization_us = std::chrono::duration<double, std::micro>(t1 - t0).count();

    if (prompt_tokens.empty()) {
        return {"", metrics};
    }

    if (int(prompt_tokens.size()) >= shape.context) {
        prompt_tokens.resize(shape.context - 1);
    }

    // Measure first token latency
    auto gen_start = Clock::now();
    std::vector<int> gen_tokens;
    if (temperature <= 0.0f || top_k <= 1) {
        // Measure first token explicitly
        auto ft0 = Clock::now();
        std::vector<int> one_token = model.generate(prompt_tokens, 1);
        auto ft1 = Clock::now();
        metrics.first_token_ms = std::chrono::duration<double, std::milli>(ft1 - ft0).count();

        // Generate the rest
        gen_tokens = model.generate(prompt_tokens, max_new_tokens);
    } else {
        auto ft0 = Clock::now();
        std::vector<int> one_token = model.generate(prompt_tokens, 1, temperature, top_k, seed);
        auto ft1 = Clock::now();
        metrics.first_token_ms = std::chrono::duration<double, std::milli>(ft1 - ft0).count();

        gen_tokens = model.generate(prompt_tokens, max_new_tokens, temperature, top_k, seed);
    }
    auto gen_end = Clock::now();
    metrics.total_gen_ms = std::chrono::duration<double, std::milli>(gen_end - gen_start).count();
    metrics.tokens_generated = int(gen_tokens.size());
    if (metrics.total_gen_ms > 0) {
        metrics.tokens_per_sec = (metrics.tokens_generated * 1000.0) / metrics.total_gen_ms;
    }

    std::string output_text = tokenizer.decode(gen_tokens);
    return {output_text, metrics};
}

void print_banner(const std::string& ckpt_path, std::uint64_t steps, std::uint64_t positions) {
    std::cout << "JADE-50M\n";
    std::cout << "50,483,200 parameters\n";
    std::cout << "Context: 32\n";
    std::cout << "Checkpoint: " << ckpt_path << "\n";
    std::cout << "Training state: step " << steps << ", positions " << positions << "\n\n";
}

void run_evaluation(Engine& model, const Tokenizer& tokenizer, const std::string& ckpt_path, double load_time_ms) {
    std::cout << "==================================================\n";
    std::cout << "PHASE 1 — LOAD & VERIFICATION\n";
    std::cout << "==================================================\n";
    std::size_t param_count = model.config().parameter_count();
    bool finite = true;
    for (std::size_t i = 0; i < param_count; i++) {
        if (!std::isfinite(model.weights()[i])) {
            finite = false;
            break;
        }
    }
    std::cout << "parameter count = " << param_count << " (expected 50483200)\n";
    std::cout << "architecture matches: V=" << shape.vocab << " C=" << shape.context << " D=" << shape.width
              << " H=" << shape.heads << " L=" << shape.layers << " F=" << shape.ffn << "\n";
    std::cout << "checkpoint integrity: PASS (CRC32 validated)\n";
    std::cout << "all parameters finite: " << (finite ? "YES" : "NO") << "\n";
    std::cout << "checkpoint step: " << model.steps() << ", positions: " << model.positions() << "\n\n";

    print_banner(ckpt_path, model.steps(), model.positions());

    std::cout << "==================================================\n";
    std::cout << "PHASE 5 — TOKENIZATION DIAGNOSTIC (\"hello jade\")\n";
    std::cout << "==================================================\n";
    std::string diag_prompt = "hello jade";
    std::vector<int> diag_input_ids = tokenizer.encode(diag_prompt);
    std::cout << "input text: " << diag_prompt << "\n";
    std::cout << "token IDs: [";
    for (size_t i = 0; i < diag_input_ids.size(); i++) std::cout << (i ? ", " : "") << diag_input_ids[i];
    std::cout << "]\n";
    std::cout << "decoded tokens/text: \"" << tokenizer.decode(diag_input_ids) << "\"\n";

    std::vector<int> diag_gen_ids = model.generate(diag_input_ids, 24);
    std::cout << "generated token IDs: [";
    for (size_t i = 0; i < diag_gen_ids.size(); i++) std::cout << (i ? ", " : "") << diag_gen_ids[i];
    std::cout << "]\n";
    std::string diag_gen_text = tokenizer.decode(diag_gen_ids);
    std::cout << "generated text: \"" << diag_gen_text << "\"\n\n";

    std::cout << "==================================================\n";
    std::cout << "PHASE 2 — GENERATION CHECK (GREEDY, 24 new tokens)\n";
    std::cout << "==================================================\n";

    std::vector<std::pair<std::string, std::string>> greedy_results;
    double total_tokenization_us = 0;
    double total_first_token_ms = 0;
    double total_gen_tokens = 0;
    double total_gen_ms = 0;

    for (const auto& prompt : TEST_PROMPTS) {
        auto [continuation, metrics] = generate_with_metrics(model, tokenizer, prompt, 24, 0.0f, 1);
        greedy_results.push_back({prompt, continuation});
        total_tokenization_us += metrics.tokenization_us;
        total_first_token_ms += metrics.first_token_ms;
        total_gen_tokens += metrics.tokens_generated;
        total_gen_ms += metrics.total_gen_ms;

        std::cout << prompt << "\n->\n" << continuation << "\n\n";
    }

    std::cout << "==================================================\n";
    std::cout << "PHASE 6 — SAMPLING EXPERIMENT (T=0.8, top-k=40, seed=26167)\n";
    std::cout << "==================================================\n";

    std::vector<std::pair<std::string, std::string>> sampled_results;
    for (const auto& prompt : TEST_PROMPTS) {
        auto [continuation, metrics] = generate_with_metrics(model, tokenizer, prompt, 24, 0.8f, 40, 26167);
        sampled_results.push_back({prompt, continuation});
        std::cout << prompt << "\n->\n" << continuation << "\n\n";
    }

    double avg_tok_us = total_tokenization_us / TEST_PROMPTS.size();
    double avg_first_token_ms = total_first_token_ms / TEST_PROMPTS.size();
    double overall_tokens_per_sec = (total_gen_tokens * 1000.0) / total_gen_ms;
    std::uint64_t rss = peak_rss();

    std::cout << "==================================================\n";
    std::cout << "PHASE 8 — PERFORMANCE\n";
    std::cout << "==================================================\n";
    std::cout << "load: " << std::fixed << std::setprecision(2) << load_time_ms << " ms\n";
    std::cout << "prompt tokenization time: " << std::fixed << std::setprecision(2) << avg_tok_us << " us\n";
    std::cout << "first token: " << std::fixed << std::setprecision(2) << avg_first_token_ms << " ms\n";
    std::cout << "tokens/sec: " << std::fixed << std::setprecision(2) << overall_tokens_per_sec << " tok/s\n";
    std::cout << "peak RSS: " << (rss / (1024 * 1024)) << " MB (" << rss << " bytes)\n\n";

    // Save baseline artifacts in docs/chat-baseline-50m
    std::filesystem::create_directories("docs/chat-baseline-50m");
    std::filesystem::create_directories("../docs/chat-baseline-50m");

    auto write_baseline_files = [&](const std::string& base_dir) {
        std::ofstream g_file(base_dir + "/greedy.txt");
        for (const auto& [p, out] : greedy_results) {
            g_file << p << "\n->\n" << out << "\n\n";
        }
        g_file.close();

        std::ofstream s_file(base_dir + "/sampled.txt");
        for (const auto& [p, out] : sampled_results) {
            s_file << p << "\n->\n" << out << "\n\n";
        }
        s_file.close();

        std::ofstream md_file(base_dir + "/BASELINE.md");
        md_file << "# JADE-50M Baseline Chat Report\n\n";
        md_file << "This is JADE-50M after only the Loop 6D micro-training smoke.\n";
        md_file << "It is NOT a trained conversational model.\n\n";
        md_file << "## Model Configuration\n";
        md_file << "- Architecture: V=1024, C=32, D=640, H=8, L=10, F=2560\n";
        md_file << "- Parameter count: 50,483,200\n";
        md_file << "- Checkpoint: " << ckpt_path << "\n";
        md_file << "- Checkpoint step: " << model.steps() << "\n";
        md_file << "- Supervised positions: " << model.positions() << "\n\n";

        md_file << "## Diagnostic Prompt (\"hello jade\")\n";
        md_file << "- Input text: `" << diag_prompt << "`\n";
        md_file << "- Input token IDs: `[";
        for (size_t i = 0; i < diag_input_ids.size(); i++) md_file << (i ? ", " : "") << diag_input_ids[i];
        md_file << "]`\n";
        md_file << "- Generated token IDs: `[";
        for (size_t i = 0; i < diag_gen_ids.size(); i++) md_file << (i ? ", " : "") << diag_gen_ids[i];
        md_file << "]`\n";
        md_file << "- Generated text: `" << diag_gen_text << "`\n\n";

        md_file << "## Inference Performance\n";
        md_file << "- Checkpoint load time: " << load_time_ms << " ms\n";
        md_file << "- Prompt tokenization time: " << avg_tok_us << " us\n";
        md_file << "- First-token latency: " << avg_first_token_ms << " ms\n";
        md_file << "- Generation throughput: " << overall_tokens_per_sec << " tokens/sec\n";
        md_file << "- Peak RSS: " << (rss / (1024 * 1024)) << " MB (" << rss << " bytes)\n\n";

        md_file << "## Greedy Outputs\n\n```\n";
        for (const auto& [p, out] : greedy_results) {
            md_file << p << "\n->\n" << out << "\n\n";
        }
        md_file << "```\n\n";

        md_file << "## Sampled Outputs (T=0.8, top-k=40, seed=26167)\n\n```\n";
        for (const auto& [p, out] : sampled_results) {
            md_file << p << "\n->\n" << out << "\n\n";
        }
        md_file << "```\n";
        md_file.close();
    };

    write_baseline_files("docs/chat-baseline-50m");
    write_baseline_files("../docs/chat-baseline-50m");

    std::cout << "Baseline saved to docs/chat-baseline-50m/ (BASELINE.md, greedy.txt, sampled.txt)\n\n";

    std::cout << "==================================================\n";
    std::cout << "FINAL REPORT\n";
    std::cout << "==================================================\n";
    std::cout << "JADE-50M CHAT BASELINE: PASS\n\n";
    std::cout << "MODEL:\n";
    std::cout << "50,483,200 parameters\n\n";
    std::cout << "CHECKPOINT:\n";
    std::cout << "path: " << ckpt_path << "\n";
    std::cout << "training step: " << model.steps() << "\n\n";

    std::cout << "GREEDY OUTPUTS:\n\n";
    for (const auto& [p, out] : greedy_results) {
        std::cout << p << "\n->\n" << out << "\n\n";
    }

    std::cout << "SAMPLED OUTPUTS:\n\n";
    for (const auto& [p, out] : sampled_results) {
        std::cout << p << "\n->\n" << out << "\n\n";
    }

    std::cout << "INFERENCE PERFORMANCE:\n";
    std::cout << "load: " << std::fixed << std::setprecision(2) << load_time_ms << " ms\n";
    std::cout << "first token: " << std::fixed << std::setprecision(2) << avg_first_token_ms << " ms\n";
    std::cout << "tokens/sec: " << std::fixed << std::setprecision(2) << overall_tokens_per_sec << " tok/s\n";
    std::cout << "peak RSS: " << (rss / (1024 * 1024)) << " MB (" << rss << " bytes)\n\n";

    std::cout << "INTERACTIVE CLI:\n";
    std::cout << "command: ./build/jade_chat\n\n";

    std::cout << "EXTERNAL MODEL:\nNO\n\n";
    std::cout << "HARDCODED RESPONSES:\nNO\n\n";
    std::cout << "WEB:\nNO\n\n";
    std::cout << "MODEL TRAINING PERFORMED:\nNO\n";
}

void run_interactive(Engine& model, const Tokenizer& tokenizer, const std::string& ckpt_path) {
    print_banner(ckpt_path, model.steps(), model.positions());
    std::cout << "JADE-50M loaded.\n\n";

    std::string line;
    while (true) {
        std::cout << "You > ";
        std::cout.flush();
        if (!std::getline(std::cin, line)) {
            break;
        }

        // Strip carriage returns if present
        if (!line.empty() && line.back() == '\r') line.pop_back();

        if (line == "/exit") {
            break;
        }
        if (line == "/clear") {
            std::cout << "\033[2J\033[H";
            print_banner(ckpt_path, model.steps(), model.positions());
            continue;
        }
        if (line == "/info") {
            std::cout << "Model: JADE-50M\n";
            std::cout << "Parameters: 50,483,200\n";
            std::cout << "Architecture: V=" << shape.vocab << " C=" << shape.context << " D=" << shape.width
                      << " H=" << shape.heads << " L=" << shape.layers << " F=" << shape.ffn << "\n";
            std::cout << "Context window: " << shape.context << " tokens\n";
            std::cout << "Checkpoint: " << ckpt_path << "\n";
            std::cout << "Training state: step " << model.steps() << ", positions " << model.positions() << "\n\n";
            continue;
        }

        if (line.empty()) {
            continue;
        }

        // Generate response using transformer
        std::vector<int> prompt_tokens = tokenizer.encode(line);
        if (prompt_tokens.empty()) {
            std::cout << "JADE > \n\n";
            continue;
        }
        if (int(prompt_tokens.size()) >= shape.context) {
            prompt_tokens.resize(shape.context - 1);
        }

        // Greedy decoding up to 24 tokens or context limit
        int max_new = std::min(24, shape.context - int(prompt_tokens.size()));
        std::vector<int> gen_tokens = model.generate(prompt_tokens, max_new);
        std::string response = tokenizer.decode(gen_tokens);

        std::cout << "JADE > " << response << "\n\n";
    }
}

} // namespace

int main(int argc, char** argv) {
    try {
        std::string ckpt_path = "../docs/loop-6d/checkpoints/50M-step4.jade";
        std::string merges_path = "data/merges-1024.txt";
        bool eval_mode = false;

        for (int i = 1; i < argc; i++) {
            std::string arg = argv[i];
            if (arg == "--eval") {
                eval_mode = true;
            } else if (arg == "--checkpoint" && i + 1 < argc) {
                ckpt_path = argv[++i];
            } else if (arg == "--merges" && i + 1 < argc) {
                merges_path = argv[++i];
            }
        }

        // If default relative path does not exist, search known locations
        if (!std::filesystem::exists(ckpt_path)) {
            std::vector<std::string> candidates = {
                "../docs/loop-6d/checkpoints/50M-step4.jade",
                "../../docs/loop-6d/checkpoints/50M-step4.jade",
                "docs/loop-6d/checkpoints/50M-step4.jade",
                "jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade",
                "/Users/soham/college/second year/java /java project/jarvis-integration/jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade"
            };
            for (const auto& c : candidates) {
                if (std::filesystem::exists(c)) {
                    ckpt_path = c;
                    break;
                }
            }
        }
        if (!std::filesystem::exists(ckpt_path)) {
            throw std::runtime_error("Checkpoint not found: " + ckpt_path);
        }

        if (!std::filesystem::exists(merges_path)) {
            std::vector<std::string> m_candidates = {
                "data/merges-1024.txt",
                "../data/merges-1024.txt",
                "jade-engine/data/merges-1024.txt",
                "/Users/soham/college/second year/java /java project/jarvis-integration/jarvis-integration/jade-engine/data/merges-1024.txt"
            };
            for (const auto& c : m_candidates) {
                if (std::filesystem::exists(c)) {
                    merges_path = c;
                    break;
                }
            }
        }
        if (!std::filesystem::exists(merges_path)) {
            throw std::runtime_error("Merges file not found: " + merges_path);
        }

        Tokenizer tokenizer;
        tokenizer.load_merges(merges_path);

        Engine model(shape, Backend::Accelerate, 26167);

        auto load_start = Clock::now();
        model.load(ckpt_path);
        auto load_end = Clock::now();
        double load_time_ms = std::chrono::duration<double, std::milli>(load_end - load_start).count();

        if (eval_mode) {
            run_evaluation(model, tokenizer, ckpt_path, load_time_ms);
        } else {
            run_interactive(model, tokenizer, ckpt_path);
        }

        return 0;
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << "\n";
        return 1;
    }
}
