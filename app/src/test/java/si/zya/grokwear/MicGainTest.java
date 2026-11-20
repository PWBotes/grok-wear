package si.zya.grokwear;

import static org.junit.Assert.*;
import org.junit.Test;

public class MicGainTest {
    private static byte[] tone(int amplitude,int samples){byte[] b=new byte[samples*2];
        for(int i=0;i<samples;i++){int v=(int)Math.round(amplitude*Math.sin(i*2*Math.PI*440/24000));b[2*i]=(byte)v;b[2*i+1]=(byte)(v>>8);}return b;}
    private static int sample(byte[] b,int i){return (short)((b[2*i]&255)|(b[2*i+1]<<8));}

    @Test public void clipNeverWraps(){
        assertEquals(32767,MicGain.clip(1e9));assertEquals(-32767,MicGain.clip(-1e9));
        assertEquals(1000,MicGain.clip(1000));assertTrue(MicGain.clip(30000)<32767&&MicGain.clip(30000)>24000);
    }
    @Test public void offPassesThrough(){
        MicGain g=new MicGain(MicGain.OFF);byte[] b=tone(300,1024);byte[] copy=b.clone();g.process(b,b.length,true);assertArrayEquals(copy,b);
    }
    @Test public void disabledPassesThrough(){
        MicGain g=new MicGain(MicGain.AUTO);byte[] b=tone(300,1024);byte[] copy=b.clone();g.process(b,b.length,false);assertArrayEquals(copy,b);
    }
    @Test public void quietSpeechIsBoostedWithinBounds(){
        MicGain g=new MicGain(MicGain.AUTO);
        byte[] noise=tone(20,1024);g.process(noise,noise.length,true); // establish a low noise floor
        for(int i=0;i<200;i++){byte[] b=tone(300,1024);g.process(b,b.length,true);}
        assertTrue("gain "+g.gain(),g.gain()>4&&g.gain()<=12.0001);
        byte[] b=tone(300,1024);g.process(b,b.length,true);
        assertTrue(Math.sqrt(g.postSumSq/g.samples)>Math.sqrt(g.preSumSq/g.samples)*4);
    }
    @Test public void loudSpeechBacksOffAndNeverWraps(){
        MicGain g=new MicGain(MicGain.HIGH);
        for(int i=0;i<20;i++){byte[] b=tone(20000,1024);g.process(b,b.length,true);
            for(int k=1;k<1024;k++){int a=sample(b,k-1),c=sample(b,k);assertTrue("jump at "+k,Math.abs(c-a)<20000);}}
        assertTrue("gain "+g.gain(),g.gain()<1.5);
    }
}
