package si.zya.grokwear;

import android.content.Context;
import android.media.MediaRecorder;

/**
 * Which microphone source a call records from. "Auto" starts with voice recognition (as dictation does), then
 * the plain mic, and uses the call source (voice processing, echo cancelling) only if neither opens or both
 * capture digital silence: on an OPPO Watch X the call source attenuates speech to near silence.
 * A fixed choice records from that source only.
 */
final class MicSource {
    private MicSource(){}
    static final String TAG="GrokWearMic";
    static final int AUTO=0,CALL=1,RECOGNITION=2,PLAIN=3;
    private static final String PREFS="grokwear_ui",KEY="mic_source";
    static final String[] LABELS={"Auto","Call","Voice recognition","Plain mic"};

    static int get(Context context){
        try{int v=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(KEY,AUTO);return v>=AUTO&&v<=PLAIN?v:AUTO;}
        catch(RuntimeException unavailable){return AUTO;}
    }
    static void set(Context context,int value){
        try{context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(KEY,value>=AUTO&&value<=PLAIN?value:AUTO).apply();}
        catch(RuntimeException ignored){}
    }
    /** Mic boost (MicGain): Auto, Off, High; saved as mic_boost. */
    private static final String BOOST="mic_boost";
    static final String[] BOOST_LABELS={"Auto","Off","High"};
    static int boost(Context context){
        try{int v=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(BOOST,MicGain.AUTO);return v>=0&&v<=2?v:MicGain.AUTO;}
        catch(RuntimeException unavailable){return MicGain.AUTO;}
    }
    static void setBoost(Context context,int value){
        try{context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(BOOST,value>=0&&value<=2?value:MicGain.AUTO).apply();}
        catch(RuntimeException ignored){}
    }
    static int nextBoost(int value){return value>=2?0:value+1;}
    static String boostLabel(int value){return BOOST_LABELS[value>=0&&value<=2?value:0];}
    /** Bot voice boost: Normal (0 dB), Loud (+9 dB, default), Max (+15 dB); saved as voice_boost. */
    private static final String VOICE="voice_boost";
    static final int VOICE_NORMAL=0,VOICE_LOUD=1,VOICE_MAX=2;
    static final String[] VOICE_LABELS={"Normal","Loud","Max"};
    private static final int[] VOICE_MB={0,900,1500};
    static int voiceBoost(Context context){
        try{int v=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(VOICE,VOICE_LOUD);return v>=0&&v<=2?v:VOICE_LOUD;}
        catch(RuntimeException unavailable){return VOICE_LOUD;}
    }
    static void setVoiceBoost(Context context,int value){
        try{context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(VOICE,value>=0&&value<=2?value:VOICE_LOUD).apply();}
        catch(RuntimeException ignored){}
    }
    static int nextVoiceBoost(int value){return value>=2?0:value+1;}
    static String voiceBoostLabel(int value){return VOICE_LABELS[value>=0&&value<=2?value:VOICE_LOUD];}
    static int voiceBoostMb(int value){return VOICE_MB[value>=0&&value<=2?value:VOICE_LOUD];}
    /** Keep the screen on during a call while Grok Wear is open; saved as keep_screen_on, default On. */
    private static final String KEEP="keep_screen_on";
    static boolean keepScreenOn(Context context){
        try{return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEEP,true);}
        catch(RuntimeException unavailable){return true;}
    }
    static void setKeepScreenOn(Context context,boolean on){
        try{context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(KEEP,on).apply();}
        catch(RuntimeException ignored){}
    }
    static int next(int value){return value>=PLAIN?AUTO:value+1;}
    static String label(int value){return LABELS[value>=AUTO&&value<=PLAIN?value:AUTO];}

    /** Audio sources to try, in order, for a setting. */
    static int[] order(int setting){
        switch(setting){
            case CALL:return new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION};
            case RECOGNITION:return new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION};
            case PLAIN:return new int[]{MediaRecorder.AudioSource.MIC};
            default:return new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC,MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        }
    }
    static String name(int source){
        switch(source){
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.MIC:return "MIC";
            default:return "source_"+source;
        }
    }

    /** Peak sample magnitude of 16-bit little-endian PCM. */
    static int peak(byte[] pcm,int length){int max=0;
        for(int i=0;i+1<length;i+=2){int s=Math.abs((int)(short)((pcm[i]&255)|(pcm[i+1]<<8)));if(s>max)max=s;}
        return max;
    }
    /** Captured audio whose peak never rose above this is digital silence: a real mic always has a noise floor. */
    static final int SILENT_PEAK=4;
    /** About 1.5 s of 24 kHz 16-bit mono must be heard before judging silence. */
    static final long PROBE_BYTES=24000L*2*3/2;
}
