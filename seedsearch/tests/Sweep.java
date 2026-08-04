import com.google.common.collect.BiMap; import com.google.common.collect.HashBiMap;
import java.util.Random;
public class Sweep {
    static final short DIVIDER=5000;
    record F(String reg,int chance,int min,int max){}
    static final F[] CFG={new F("gas_natural_gas",20,10,350),new F("liquid_light_oil",20,10,350),
        new F("liquid_medium_oil",20,0,625),new F("liquid_heavy_oil",20,0,625),new F("oil",20,0,625)};
    public static void main(String[] a){
        BiMap<String,F> m=HashBiMap.create(); int mc=0;
        for(F f:CFG){m.put(f.reg(),f); mc+=f.chance();}
        Random meta=new Random(42);
        for(int t=0;t<20000;t++){
            long s=meta.nextLong();
            int cx=meta.nextInt(4001)-2000, cz=meta.nextInt(4001)-2000;
            XSTR r=new XSTR(s+(cx>>3)+8267L*(cz>>3));
            int rnd=r.nextInt(1000); F pick=null;
            for(BiMap.Entry<String,F> e:m.entrySet()){int c=e.getValue().chance()*1000/mc;
                if(rnd<=c){pick=e.getValue();break;} rnd-=c;}
            if(pick==null){System.out.println(s+" "+cx+" "+cz+" NULL"); continue;}
            int smax=(int)Math.floor(Math.pow(pick.max()*100.d*DIVIDER,0.2d));
            double smin=Math.pow(pick.min()*100.d*DIVIDER,0.2d);
            double sam=Math.max(smin,r.nextInt(smax)+r.nextDouble());
            int va=(int)(Math.pow(sam,5)/100);
            for(int i=0;i<(((cx&0x7)<<3)|cz&0x7);i++) r.next(24);
            int amt=(int)((float)va*(0.75f+(r.nextFloat()/2f)));
            System.out.println(s+" "+cx+" "+cz+" "+pick.reg()+" "+va+" "+amt);
        }
    }
}
