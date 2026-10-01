#include "jade/engine.hpp"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <map>
#include <random>
#include <iostream>
#include <iomanip>
#include <limits>
#include <stdexcept>
#include <vector>

using namespace jade;
static int checks=0;
static void check(bool condition,const char* message){++checks;if(!condition)throw std::runtime_error(message);}
static void close(float actual,float expected,float tolerance,const char* message){check(std::abs(actual-expected)<=tolerance,message);}
static void parity(const char* path,Backend backend){
    std::ifstream in(path);check(bool(in),"Java fixture missing");
    std::map<std::string,std::vector<double>> values;
    std::string kind,name;double reference_loss=0;
    while(in>>kind){
        if(kind=="CONFIG"){int x;for(int i=0;i<6;i++)in>>x;}
        else if(kind=="INPUT"||kind=="TARGET"){int x;for(int i=0;i<4;i++)in>>x;}
        else if(kind=="LOSS")in>>reference_loss;
        else {std::size_t n;in>>name>>n;auto& data=values[kind+":"+name];data.resize(n);for(double& x:data)in>>x;}
    }
    Engine engine({32,4,8,2,1,16},backend);
    for(const auto& parameter:engine.layout()){
        const auto& source=values.at("PARAM:"+parameter.name);
        for(std::size_t i=0;i<source.size();i++)engine.weights()[parameter.offset+i]=float(source[i]);
    }
    const int input[]={1,2,3,4},target[]={2,3,4,5};
    engine.zero_grad();const float loss=engine.backward(input,target,4);
    double max_forward=0,rel_forward=0,max_gradient=0,rel_gradient=0;
    for(const auto& trace:engine.trace(0,4)){
        const auto& expected=values.at("TRACE:"+trace.name);check(expected.size()==trace.size,"trace shape");
        for(std::size_t i=0;i<trace.size;i++){
            const double error=std::abs(trace.values[i]-expected[i]);
            max_forward=std::max(max_forward,error);rel_forward=std::max(rel_forward,error/std::max(1e-6,std::abs(expected[i])));
            check(error<=2e-6+2e-5*std::abs(expected[i]),"Java forward parity");
        }
    }
    std::vector<float> analytical(engine.gradients(),engine.gradients()+engine.config().parameter_count());
    for(const auto& parameter:engine.layout()){
        const auto& expected=values.at("GRAD:"+parameter.name);
        for(std::size_t i=0;i<expected.size();i++){
            const double error=std::abs(analytical[parameter.offset+i]-expected[i]);
            max_gradient=std::max(max_gradient,error);rel_gradient=std::max(rel_gradient,error/std::max(1e-6,std::abs(expected[i])));
            check(error<=3e-6+3e-4*std::abs(expected[i]),"Java gradient parity");
        }
    }
    check(std::abs(loss-reference_loss)<2e-6,"Java loss parity");
    double fd_abs=0,fd_rel=0;
    for(std::size_t i=0;i<analytical.size();i++){
        const float original=engine.weights()[i],epsilon=.001f;
        engine.weights()[i]=original+epsilon;const float plus=engine.forward_loss(input,target,4);
        engine.weights()[i]=original-epsilon;const float minus=engine.forward_loss(input,target,4);
        engine.weights()[i]=original;
        const float numerical=(plus-minus)/(2*epsilon);
        const double error=std::abs(numerical-analytical[i]);
        fd_abs=std::max(fd_abs,error);fd_rel=std::max(fd_rel,error/std::max(.001,double(std::abs(analytical[i]))));
        check(error<.002+ .03*std::abs(analytical[i]),"finite difference gradient");
    }
    for(const auto& parameter:engine.layout()){
        const auto& gradient=values.at("GRAD:"+parameter.name);
        for(std::size_t i=0;i<gradient.size();i++)engine.gradient_data()[parameter.offset+i]=float(gradient[i]);
    }
    for(int step=0;step<3;step++)engine.adamw({});
    double adam_error=0;
    for(const auto& parameter:engine.layout())for(const auto& item:{
            std::make_pair("ADAM_PARAM:",static_cast<const float*>(engine.weights())),std::make_pair("ADAM_M:",engine.first_moments()),
            std::make_pair("ADAM_V:",engine.second_moments())}){
        const auto& expected=values.at(std::string(item.first)+parameter.name);
        for(std::size_t i=0;i<expected.size();i++){
            const double error=std::abs(item.second[parameter.offset+i]-expected[i]);adam_error=std::max(adam_error,error);
            check(error<3e-6,"Java three-step AdamW parity");
        }
    }
    const float after=engine.forward_loss(input,target,4);check(after<loss,"controlled optimization reduces loss");
    std::cout<<"PARITY backend="<<(backend==Backend::Naive?"naive":"accelerate")
             <<" forward_abs="<<max_forward<<" forward_rel_floor1e-6="<<rel_forward
             <<" loss_java="<<reference_loss<<" loss_cpp="<<loss<<" gradient_abs="<<max_gradient
             <<" gradient_rel_floor1e-6="<<rel_gradient<<" finite_difference_abs="<<fd_abs
             <<" finite_difference_rel_floor1e-3="<<fd_rel<<" adamw_abs="<<adam_error<<"\n";
}

int main(int argc,char** argv){
    try{
        std::cout<<std::setprecision(12);
        Tensor tensor(2,3);
        check(tensor.size()==6&&tensor.rows()==2&&tensor.columns()==3,"tensor shape");
        tensor.at(1,2)=4;close(tensor.at(1,2),4,0,"tensor indexing");
        try{Tensor bad(std::numeric_limits<std::size_t>::max(),2);check(false,"tensor overflow missed");}
        catch(const std::overflow_error&){}
        const float a[]={1,-2,3,0,4,-1},b[]={2,0,-1,5,3,2};
        float naive[4]={},fast[4]={};
        gemm(Backend::Naive,false,false,2,2,3,a,b,naive);
        close(naive[0],13,1e-6f,"matmul 00");close(naive[1],-4,1e-6f,"matmul 01");
        close(naive[2],-7,1e-6f,"matmul 10");close(naive[3],18,1e-6f,"matmul 11");
#ifdef JADE_HAS_ACCELERATE
        gemm(Backend::Accelerate,false,false,2,2,3,a,b,fast);
        for(int i=0;i<4;i++)close(fast[i],naive[i],1e-6f,"Accelerate GEMM parity");
#endif
        float zeros[6]={},zero_result[4];
        std::fill_n(zero_result,4,std::numeric_limits<float>::quiet_NaN());
        gemm(Backend::Naive,false,false,2,2,3,zeros,b,zero_result);
        for(float value:zero_result)close(value,0,0,"GEMM beta zero must overwrite output");
        float huge[]={1e30f,-1e30f},huge_norm[2];rmsnorm(huge,huge_norm,1,2);
        close(huge_norm[0],1,1e-6f,"RMSNorm finite large input");
        close(gelu_derivative(1e30f),1,0,"saturated GELU derivative");
        close(gelu_derivative(-1e30f),0,0,"negative saturated GELU derivative");
        std::mt19937 random(42);std::uniform_real_distribution<float> uniform(-1,1);
        for(bool ta:{false,true})for(bool tb:{false,true}){
            float left[15],right[12],result[20],optimized[20];
            for(float& value:left)value=uniform(random);
            for(float& value:right)value=uniform(random);
            std::fill_n(result,20,.25f);std::fill_n(optimized,20,.25f);
            gemm(Backend::Naive,ta,tb,5,4,3,left,right,result,.7f,.2f);
            for(int i=0;i<5;i++)for(int j=0;j<4;j++){
                double reference=.05;
                for(int k=0;k<3;k++)reference+=.7*double(left[ta?k*5+i:i*3+k])*right[tb?j*3+k:k*4+j];
                close(result[i*4+j],float(reference),1e-6f,"random rectangular transpose GEMM");
            }
#ifdef JADE_HAS_ACCELERATE
            gemm(Backend::Accelerate,ta,tb,5,4,3,left,right,optimized,.7f,.2f);
            for(int i=0;i<20;i++)close(optimized[i],result[i],1e-6f,"random Accelerate transpose GEMM");
#endif
        }
        float norm[6],upstream[]={1,2,3,4,5,6},dnorm[6];
        rmsnorm(a,norm,2,3);rmsnorm_backward(a,upstream,dnorm,2,3);
        check(std::isfinite(norm[0])&&std::isfinite(dnorm[5]),"RMSNorm finite");
        const float eps=1e-3f;
        for(int i=0;i<6;i++){
            float perturbed[6];std::copy_n(a,6,perturbed);perturbed[i]+=eps;
            float positive[6];rmsnorm(perturbed,positive,2,3);
            perturbed[i]-=2*eps;float negative[6];rmsnorm(perturbed,negative,2,3);
            float numerical=0;for(int j=0;j<3;j++)numerical+=(positive[(i/3)*3+j]-negative[(i/3)*3+j])*upstream[(i/3)*3+j]/(2*eps);
            close(dnorm[i],numerical,.001f,"RMSNorm derivative");
        }
        for(float x:{-3.f,-.5f,0.f,1.f,3.f}){
            close(gelu_derivative(x),(gelu(x+eps)-gelu(x-eps))/(2*eps),.002f,"GELU derivative");
        }
        float scores[]={1,2,3},probabilities[3];softmax(scores,probabilities,3);
        close(probabilities[0]+probabilities[1]+probabilities[2],1,1e-6f,"softmax sum");
        check(probabilities[0]<probabilities[1]&&probabilities[1]<probabilities[2],"softmax order");
        Config config{32,4,8,2,1,16};check(config.parameter_count()==1056,"tiny parameter count");
        const Config five_m{1024,32,256,4,6,1024},twenty_m{1024,32,448,8,8,1792},
                     fifty_m{1024,32,640,8,10,2560};
        check(five_m.parameter_count()==5251072,"5M remains accepted");
        check(twenty_m.parameter_count()==20199424,"exact 20M candidate accepted");
        check(fifty_m.parameter_count()==50483200,"exact 50M candidate accepted");
        const auto fifty_plan=plan_memory(fifty_m);
        check(fifty_plan.persistent_bytes==807731200&&fifty_plan.workspace_bytes==17219712&&
              fifty_plan.checkpoint_bytes==605798468,"50M memory preflight");
        const auto planned=plan_memory(twenty_m);
        check(planned.persistent_bytes==323190784&&planned.workspace_bytes==10133632&&
              planned.checkpoint_bytes==242393156,"20M memory preflight");
        bool larger_rejected=false;
        try{Config{1024,32,768,12,10,3072}.validate();}
        catch(const std::invalid_argument&){larger_rejected=true;}
        check(larger_rejected,">60M allocation guard");
        larger_rejected=false;
        try{Config{1024,32,448,6,8,1792}.validate();}
        catch(const std::invalid_argument&){larger_rejected=true;}
        check(larger_rejected,"invalid attention head split");
        larger_rejected=false;
        try{Config{std::numeric_limits<int>::max(),4096,4096,1,64,4096}.parameter_count();}
        catch(const std::invalid_argument&){larger_rejected=true;}
        check(larger_rejected,"malformed dimensions rejected before arithmetic overflow");
        larger_rejected=false;
        try{five_m.validate({61'000'000,2ull*1024*1024*1024});}
        catch(const std::invalid_argument&){larger_rejected=true;}
        check(larger_rejected,"engineering ceiling cannot be made unlimited");
        const auto allocations_before=Tensor::allocations().allocations;
        larger_rejected=false;
        try{Engine unsafe(five_m,Backend::Naive,12,{60'000'000,100ull*1024*1024});}
        catch(const std::invalid_argument&){larger_rejected=true;}
        check(larger_rejected&&Tensor::allocations().allocations==allocations_before,
              "memory budget rejected before tensor allocation");
        Engine model(config,Backend::Naive,12);
        const int input[]={1,2,3,4},targets[]={2,3,4,5},future[]={1,7,3,4};
        float loss=model.forward_loss(input,targets,4);
        check(std::isfinite(loss)&&loss>0,"cross entropy finite");
        auto first=model.trace(0,4);std::vector<float> first_logits(first.back().values,first.back().values+first.back().size);
        model.forward_loss(future,targets,4);auto changed=model.trace(0,4);
        for(int j=0;j<32;j++)close(changed.back().values[j],first_logits[j],1e-6f,"causal attention");
        auto baseline=model.generate({1,2},2);check(baseline==model.generate({1,2},2),"generation deterministic");
        model.zero_grad();float backward_loss=model.backward(input,targets,4);
        close(backward_loss,loss,1e-6f,"forward/backward loss");
        const float original=model.weights()[0];model.adamw({},1);
        check(model.steps()==1&&model.positions()==4&&model.weights()[0]!=original,"AdamW update");
        Engine single(config,Backend::Naive,12),averaged(config,Backend::Naive,12);
        single.zero_grad();averaged.zero_grad();
        single.backward(input,targets,4);averaged.backward(input,targets,4);
        for(std::size_t i=0;i<config.parameter_count();i++)averaged.gradient_data()[i]*=2;
        single.adamw({},1);averaged.adamw({},2);
        for(std::size_t i=0;i<config.parameter_count();i++){
            close(averaged.weights()[i],single.weights()[i],0,"batch gradient mean update");
            close(averaged.first_moments()[i],single.first_moments()[i],0,"batch first moment");
            close(averaged.second_moments()[i],single.second_moments()[i],0,"batch second moment");
        }
        check(averaged.positions()==8&&single.positions()==4,"batch position count");
        const std::filesystem::path path=std::filesystem::temp_directory_path()/"jade-engine-test.jade";
        model.save(path.string());Engine loaded(config,Backend::Naive,99);loaded.load(path.string());
        check(loaded.steps()==model.steps()&&loaded.positions()==model.positions(),"checkpoint counters");
        for(std::size_t i=0;i<config.parameter_count();i++)close(loaded.weights()[i],model.weights()[i],0,"checkpoint weights");
        check(loaded.generate({1,2},2)==model.generate({1,2},2),"checkpoint generation");
        loaded.zero_grad();model.zero_grad();loaded.backward(input,targets,4);model.backward(input,targets,4);
        loaded.adamw({},1);model.adamw({},1);
        for(std::size_t i=0;i<config.parameter_count();i++)close(loaded.weights()[i],model.weights()[i],0,"checkpoint resume");
        // Rejected checkpoints must not mutate an already-loaded model.
        const float saved_weight=loaded.weights()[0];const auto saved_step=loaded.steps();
        {std::fstream corrupt(path,std::ios::binary|std::ios::in|std::ios::out);
         corrupt.seekp(80);const char byte=127;corrupt.write(&byte,1);}
        bool rejected=false;try{loaded.load(path.string());}catch(const std::runtime_error&){rejected=true;}
        check(rejected&&loaded.weights()[0]==saved_weight&&loaded.steps()==saved_step,"corrupt checkpoint atomic rejection");
        model.save(path.string());std::filesystem::resize_file(path,100);
        rejected=false;try{loaded.load(path.string());}catch(const std::runtime_error&){rejected=true;}
        check(rejected&&loaded.weights()[0]==saved_weight,"truncated checkpoint rejected");
        model.save(path.string());Engine different({32,4,8,4,1,16},Backend::Naive);
        rejected=false;try{different.load(path.string());}catch(const std::runtime_error&){rejected=true;}
        check(rejected,"same parameter count different heads rejected");
        AdamWConfig invalid;invalid.learning_rate=std::numeric_limits<float>::quiet_NaN();
        rejected=false;try{loaded.adamw(invalid);}catch(const std::invalid_argument&){rejected=true;}
        check(rejected&&loaded.weights()[0]==saved_weight,"nonfinite optimizer setting rejected before mutation");
        std::filesystem::remove(path);
        check(argc==2,"Supply Java parity fixture");
        parity(argv[1],Backend::Naive);
#ifdef JADE_HAS_ACCELERATE
        parity(argv[1],Backend::Accelerate);
#endif
        std::cout<<"PASS checks="<<checks<<"\n";return 0;
    }catch(const std::exception& error){std::cerr<<"FAIL "<<error.what()<<" checks="<<checks<<"\n";return 1;}
}
