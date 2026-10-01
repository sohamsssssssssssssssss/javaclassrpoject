import com.jade.brain.config.BrainConfig;

/** Arithmetic-only 20M planner; deliberately does not instantiate a model. */
public final class Planner20M {
    static long mul(long a,long b){return Math.multiplyExact(a,b);}
    static long add(long a,long b){return Math.addExact(a,b);}
    static long workspace(int v,int c,int d,int h,int l,int f){
        long elements=add(mul(11L+10L*l,mul(c,d)),mul(2L+2L*l,mul(c,f)));
        elements=add(elements,mul(2L,mul(c,v)));
        elements=add(elements,mul(2L*l,mul(h,mul(c,c))));
        return mul(add(elements,c),4);
    }
    public static void main(String[] args){
        if(workspace(1024,32,256,4,6,1024)!=4_620_416)throw new AssertionError("5M workspace formula mismatch");
        int v=1024,c=32,d=448,h=8,l=8,f=1792;
        long p=BrainConfig.exactParameterCount(v,c,d,l,f),each=mul(p,4),persistent=mul(each,4);
        long cache=workspace(v,c,d,h,l,f),checkpoint=add(68,mul(p,12));
        long overhead=96_010_240-(84_017_152+4_620_416);
        System.out.printf("V=%d C=%d D=%d H=%d L=%d F=%d parameters=%d%n",v,c,d,h,l,f,p);
        System.out.printf("parameterBytes=%d gradientBytes=%d firstMomentBytes=%d secondMomentBytes=%d persistentBytes=%d workspaceBytes=%d checkpointBytes=%d%n",each,each,each,each,persistent,cache,checkpoint);
        System.out.printf("estimatedTrainingRss=%d estimatedLoadRss=%d measured5MNonTensorOverhead=%d%n",add(add(persistent,cache),overhead),add(add(add(persistent,cache),mul(p,12)),overhead),overhead);
        long projected=add(mul(l,add(mul(4L,mul(d,d)),mul(2L,mul(d,f)))),mul(d,v));
        System.out.printf("projectionWeightsPerPosition=%d%n",projected);
    }
}
