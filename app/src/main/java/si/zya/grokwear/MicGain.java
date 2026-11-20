package si.zya.grokwear;

/**
 * Digital gain for raw (non-call) microphone sources, applied in place to 16-bit little-endian mono PCM
 * before it reaches the session and the level meter. Some watches (OPPO Watch X) record speech near
 * -45 dBFS from the voice-recognition and plain mic sources, too quiet for server VAD to start a turn.
 *
 * A fixed preamp, then slow automatic gain toward a target speech RMS: fast attack when speech is loud,
 * slow release. Gain only rises on frames well above the tracked noise floor, and is capped so the noise
 * floor isn't lifted past NOISE_CEILING. A soft knee then a hard clamp keep samples from wrapping around.
 * Not thread-safe: the capture thread owns it.
 */
final class MicGain {
    static final int AUTO=0,OFF=1,HIGH=2;
    static final double TARGET_RMS=3500,GATE_RMS=40,NOISE_CEILING=600,KNEE=24000,FULL=32767;
    private static final double ATTACK=0.5,RELEASE=0.02;
    final int mode; final double preamp,maxGain;
    private double gain,noiseFloor=-1;
    /** Last frame's statistics, before and after gain. */
    int prePeak,postPeak; double preSumSq,postSumSq; int samples;

    MicGain(int mode){
        this.mode=mode;
        preamp=mode==OFF?1:mode==HIGH?8:4;
        maxGain=mode==OFF?1:mode==HIGH?20:12;
        gain=preamp;
    }
    double gain(){return gain;}
    double noiseFloor(){return noiseFloor<0?0:noiseFloor;}

    /** Updates frame stats; applies gain only when enabled (else the frame passes through unchanged). */
    void process(byte[] pcm,int length,boolean enabled){
        samples=length/2;prePeak=0;preSumSq=0;
        for(int i=0;i+1<length;i+=2){int v=(short)((pcm[i]&255)|(pcm[i+1]<<8));preSumSq+=(double)v*v;if(Math.abs(v)>prePeak)prePeak=Math.abs(v);}
        double rms=samples>0?Math.sqrt(preSumSq/samples):0;
        if(!enabled||mode==OFF||samples==0){postPeak=prePeak;postSumSq=preSumSq;return;}
        // Noise floor: drops at once to a quieter frame, creeps up slowly.
        if(noiseFloor<0||rms<noiseFloor)noiseFloor=rms;else noiseFloor+=(rms-noiseFloor)*0.005;
        double from=gain,to=gain;
        boolean speech=rms>=GATE_RMS&&rms>=2*noiseFloor;
        double ceiling=Math.max(preamp,noiseFloor>0?NOISE_CEILING/noiseFloor:maxGain);
        double limit=Math.min(maxGain,ceiling);
        if(rms>0){
            double desired=Math.max(1,Math.min(limit,TARGET_RMS/rms));
            if(desired<gain)to=gain+(desired-gain)*ATTACK;          // loud: back off quickly
            else if(speech)to=gain+(desired-gain)*RELEASE;            // quiet speech: rise slowly
        }
        if(to>limit&&to>from)to=Math.max(from,limit);
        gain=to;
        postPeak=0;postSumSq=0;
        for(int i=0,k=0;i+1<length;i+=2,k++){
            double g=from+(to-from)*k/samples;
            int out=clip(((short)((pcm[i]&255)|(pcm[i+1]<<8)))*g);
            pcm[i]=(byte)out;pcm[i+1]=(byte)(out>>8);
            postSumSq+=(double)out*out;if(Math.abs(out)>postPeak)postPeak=Math.abs(out);
        }
    }
    /** Soft knee above KNEE, then a hard clamp: never wraps around. */
    static int clip(double x){
        double a=Math.abs(x);
        if(a>KNEE)a=KNEE+(FULL-KNEE)*Math.tanh((a-KNEE)/(FULL-KNEE));
        long v=Math.round(Math.copySign(Math.min(a,FULL),x));
        return (int)Math.max(-32768,Math.min(32767,v));
    }
}
