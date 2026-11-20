package si.zya.grokwear;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.NoiseSuppressor;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory duplex PCM; microphone starts only after the remote session is ready. */
final class NativeAudio implements AutoCloseable {
    interface Listener { void input(byte[] pcm,float level); void output(float level); void drained(); void failure(String safe); }
    private static final int RATE=24000,FRAME=2048;
    private final Context context; private final AudioManager manager; private final Listener listener;
    private final PcmPlaybackBuffer queue=new PcmPlaybackBuffer();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object lock=new Object();
    private volatile AudioRecord record; private volatile AudioTrack track; private volatile AudioEffect[] effects=new AudioEffect[0];
    /** Microphone sources still to try (MicSource) and the one in use; the probe judges the first ~1.5 s for silence. */
    private int[] sources; private volatile int sourceIndex; private long probeBytes; private int probePeak; private boolean probing;
    /** Capture-thread level window (~2 s) and the collapse watchdog; counts and levels only, never audio. */
    private long windowStart,windowSamples,lastSoundAt; private double windowSumSq,windowPostSumSq; private int windowPeak,windowPostPeak,restarts; private MicGain gain; private long playEndAt=Long.MIN_VALUE/2; private int windowDucked; private static final long DUCK_TAIL_MS=400;
    /** Voice boost: LoudnessEnhancer on the track's session, or (if unavailable) software gain with a soft limiter. */
    private volatile LoudnessEnhancer loudness; private volatile double softVoiceGain=1; private boolean restartArmed=true;
    private static final long LEVEL_MS=2000,COLLAPSE_MS=4000; private static final int COLLAPSE_PEAK=8,MAX_RESTARTS=3;
    private AudioFocusRequest focus; private Thread capture,playback;
    private final CaptureGate captureGate=new CaptureGate();
    /** A read belongs to one unmuted interval; never accept a result spanning stop/start. */
    static final class CaptureGate {
        private boolean muted=true;private long generation;
        synchronized void mute(boolean value){if(muted!=value){muted=value;generation++;}}
        synchronized long begin(){return muted?-1:generation;}
        synchronized boolean accepts(long ticket){return !muted&&ticket>=0&&ticket==generation;}
        synchronized boolean muted(){return muted;}
    }
    private int oldMode; private boolean changedMode,focusHeld,routed; private AudioDeviceCallback routes;
    /**
     * Like the official Android client: a connected headset (Bluetooth, wired, USB) carries the
     * call, otherwise the built-in speaker; never an earpiece. Re-run when devices come and go.
     */
    private volatile boolean callMode;
    private void route(){
        if(android.os.Build.VERSION.SDK_INT<31||!callMode)return;
        try{AudioDeviceInfo speaker=null,chosen=null;
            for(AudioDeviceInfo device:manager.getAvailableCommunicationDevices()){int type=device.getType();
                if(type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){if(speaker==null)speaker=device;}
                else if(type!=AudioDeviceInfo.TYPE_BUILTIN_EARPIECE&&type!=AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE&&chosen==null)chosen=device;}
            if(chosen==null)chosen=speaker;
            boolean ok=chosen!=null&&manager.setCommunicationDevice(chosen);if(ok)routed=true;
            android.util.Log.i(MicSource.TAG,"route comm_device="+(chosen==null?"none":chosen.getType())+" set="+ok);
        }catch(RuntimeException failed){android.util.Log.w(MicSource.TAG,"route failed="+failed.getClass().getSimpleName());}
    }
    /** Call mode (MODE_IN_COMMUNICATION, a communication device) only for the call source or a call-audio voice. */
    private void enterCallMode(){
        if(callMode)return;callMode=true;
        try{if(manager.getMode()!=AudioManager.MODE_IN_COMMUNICATION){manager.setMode(AudioManager.MODE_IN_COMMUNICATION);changedMode=true;}}catch(RuntimeException ignored){}
        route();
        if(android.os.Build.VERSION.SDK_INT>=31&&routes==null)try{routes=new AudioDeviceCallback(){
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added){if(!closed.get())route();}
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed){if(!closed.get())route();}};
            manager.registerAudioDeviceCallback(routes,null);}catch(RuntimeException ignored){}
        android.util.Log.i(MicSource.TAG,"call_mode=on mode="+safeMode());
    }
    private void leaveCallMode(){
        if(!callMode)return;callMode=false;
        if(android.os.Build.VERSION.SDK_INT>=31){
            if(routes!=null){try{manager.unregisterAudioDeviceCallback(routes);}catch(Exception ignored){}routes=null;}
            if(routed)try{manager.clearCommunicationDevice();routed=false;android.util.Log.i(MicSource.TAG,"route comm_device=cleared");}catch(Exception ignored){}}
        if(changedMode)try{if(manager.getMode()==AudioManager.MODE_IN_COMMUNICATION)manager.setMode(oldMode);changedMode=false;}catch(Exception ignored){}
        android.util.Log.i(MicSource.TAG,"call_mode=off mode="+safeMode());
    }
    private static final int[] HEADSETS={AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_HEARING_AID,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_BLE_SPEAKER};
    /**
     * The Bot's voice plays as media on the watch speaker: watches play call audio quietly, and quieter
     * still for its first seconds (found on a Galaxy Watch7). Call mode stays on, so the microphone keeps
     * its voice processing and echo cancelling. A headset keeps call audio, so its call link isn't split
     * from a media link, and so does media volume at zero, so the Bot is never silent.
     */
    static boolean voiceAsMedia(int[] outputs,int mediaVolume){
        if(mediaVolume<=0)return false;
        for(int type:outputs)for(int headset:HEADSETS)if(type==headset)return false;
        return true;
    }
    /** The volume stream matching the usage selected for this call's AudioTrack. */
    static int volumeStream(boolean media){return media?AudioManager.STREAM_MUSIC:AudioManager.STREAM_VOICE_CALL;}
    private volatile boolean media;
    /** Whether this call's voice plays as media; for the problem log. */
    boolean voiceAsMedia(){return media;}
    int volumeStream(){return volumeStream(media);}
    private int[] outputs(){
        try{AudioDeviceInfo[] devices=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);int[] types=new int[devices.length];
            for(int i=0;i<devices.length;i++)types[i]=devices[i].getType();return types;}
        catch(RuntimeException unknown){return new int[0];}
    }
    private volatile long framesWritten,framesQueued; private volatile boolean hadOutput;
    NativeAudio(Context context,Listener listener){this.context=context.getApplicationContext();this.manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.listener=listener;}
    void start(boolean initiallyMuted) {
        synchronized(this){userMuted=initiallyMuted;}captureGate.mute(initiallyMuted);
        try {
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                close();listener.failure("Microphone access is required to start voice.");return;
            }
            // Audio focus is always asked for as a call, whichever way the voice plays.
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change->{if(closed.get())return;
                    android.util.Log.i(MicSource.TAG,"focus change="+change);
                    // Like the official Android client, a short interruption (alarm, timer, assistant)
                    // pauses the call and focus coming back resumes it. A ducking request leaves the
                    // call running; only a permanent loss ends it.
                    if(change==AudioManager.AUDIOFOCUS_GAIN)focusPause(false);
                    else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)focusPause(true);
                    else if(change==AudioManager.AUDIOFOCUS_LOSS)listener.failure("Audio focus was lost. Start a new call to continue.");
                }).build();
            if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException();
            focusHeld=true;oldMode=manager.getMode();
            // Call mode is decided per microphone source in openMic, after the voice output is known.
            AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            // ~300 ms of headroom rides out a GC pause or scheduling gap on a watch without polling.
            int out=Math.max(14400,AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
            media=voiceAsMedia(outputs(),manager.getStreamVolume(AudioManager.STREAM_MUSIC));
            android.util.Log.i("GrokWearAudio","voice_output="+(media?"media":"call"));
            AudioAttributes voice=media?new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build():attributes;
            track=new AudioTrack.Builder().setAudioAttributes(voice).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(out).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException();
            try{track.setVolume(1.0f);}catch(RuntimeException ignored){}
            int voiceBoost=MicSource.voiceBoost(context),boostMb=MicSource.voiceBoostMb(voiceBoost);String voiceHow=boostVoice(boostMb);
            int musicNow=-1,musicMax=-1;try{musicNow=manager.getStreamVolume(AudioManager.STREAM_MUSIC);musicMax=manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}catch(RuntimeException ignored){}
            android.util.Log.i(MicSource.TAG,"voice_boost="+MicSource.voiceBoostLabel(voiceBoost)+" target_mB="+boostMb+" via="+voiceHow+" music_volume="+musicNow+"/"+musicMax);
            if(media&&musicMax>0&&musicNow*10<musicMax*6)android.util.Log.w(MicSource.TAG,"music_volume_low "+musicNow+"/"+musicMax+" (below 60%; raise the watch's media volume)");
            int setting=MicSource.get(context);sources=MicSource.order(setting);
            int boost=MicSource.boost(context);gain=new MicGain(boost);
            android.util.Log.i(MicSource.TAG,"call_start mic_setting="+MicSource.label(setting)+" mic_boost="+MicSource.boostLabel(boost)+" voice_output="+(media?"media":"call")+" mode="+safeMode());
            synchronized(this){if(!openMic(0))throw new IllegalStateException();}
            track.play();
            capture=new Thread(this::capture,"grok-microphone");playback=new Thread(this::play,"grok-speaker");capture.start();playback.start();
        }catch(SecurityException revoked){close();listener.failure("Microphone access was revoked. Allow microphone access before starting voice again.");}
        catch(Exception ignored){close();listener.failure("Watch audio could not start. Check microphone access and the audio route.");}
    }
    private void capture(){byte[] buffer=new byte[FRAME];int failedReads=0;windowStart=lastSoundAt=SystemClock.elapsedRealtime();try{while(!closed.get()){
        long ticket=captureGate.begin();if(ticket<0){tick(record);Thread.sleep(20);continue;}
        AudioRecord r=record;if(r==null){if(!closed.get())listener.failure("Microphone capture stopped. Start a new call.");return;}
        int n=r.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
        if(closed.get())return;if(!captureGate.accepts(ticket))continue;
        // Like the official Android client, a failed read is retried; only about 2 s of failures end the call.
        if(n<=0){if(++failedReads>=40){failedReads=0;if(fallback(r,"read_failed "+n))continue;listener.failure("Microphone capture stopped. Start a new call.");return;}Thread.sleep(50);if(tick(r))failedReads=0;continue;}
        failedReads=0;
        if(probe(r,buffer,n))continue;
        measure(buffer,n);
        byte[] pcm=Arrays.copyOf(buffer,n);try{listener.input(pcm,level(pcm));}finally{Arrays.fill(pcm,(byte)0);}
        tick(r);
    }}catch(InterruptedException ignored){Thread.currentThread().interrupt();}catch(Exception ignored){if(!closed.get())listener.failure("Microphone capture failed.");}finally{Arrays.fill(buffer,(byte)0);}}
    /** Sets up the voice boost; returns how ("off", "loudness", "software", or why it couldn't). */
    private String boostVoice(int mB){
        softVoiceGain=1;
        if(mB<=0)return "off";
        try{LoudnessEnhancer enhancer=new LoudnessEnhancer(track.getAudioSessionId());
            try{enhancer.setTargetGain(mB);enhancer.setEnabled(true);
                if(enhancer.getEnabled()){loudness=enhancer;return "loudness";}
            }catch(RuntimeException failed){}
            try{enhancer.release();}catch(Exception ignored){}
        }catch(RuntimeException unavailable){}
        softVoiceGain=Math.pow(10,mB/2000.0);
        return "software x"+String.format(java.util.Locale.US,"%.2f",softVoiceGain);
    }
    /** Software voice boost in place (fallback only); soft knee and clamp so nothing wraps around. */
    private static void boostPcm(byte[] pcm,int offset,int length,double g){
        for(int i=offset;i+1<offset+length;i+=2){int out=MicGain.clip(((short)((pcm[i]&255)|(pcm[i+1]<<8)))*g);pcm[i]=(byte)out;pcm[i+1]=(byte)(out>>8);}
    }
    /** Level stats before gain (watchdog, log), then boost raw sources in place (MicGain). */
    private void measure(byte[] pcm,int n){
        MicGain g=gain;long now=SystemClock.elapsedRealtime();
        // No boost while the Bot speaks (and briefly after): raw sources have no echo cancelling, and a boosted
        // copy of the Bot's own voice would make server VAD interrupt it. Gain holds its level meanwhile.
        if(hadOutput)playEndAt=now;boolean ducked=now-playEndAt<DUCK_TAIL_MS;if(ducked)windowDucked++;
        boolean boost=!ducked&&sources!=null&&sources[sourceIndex]!=MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        g.process(pcm,n,boost);
        windowSamples+=g.samples;windowSumSq+=g.preSumSq;windowPostSumSq+=g.postSumSq;
        if(g.prePeak>windowPeak)windowPeak=g.prePeak;if(g.postPeak>windowPostPeak)windowPostPeak=g.postPeak;
        if(g.prePeak>COLLAPSE_PEAK){lastSoundAt=SystemClock.elapsedRealtime();restartArmed=true;}
    }
    /** Every ~2 s: a level line, and the watchdog. True when the recorder was restarted. */
    private boolean tick(AudioRecord r){
        long now=SystemClock.elapsedRealtime();boolean muted=captureGate.muted(),playing=hadOutput;
        if(muted||playing)lastSoundAt=now; // Silence only counts while the caller can talk and the Bot is quiet.
        if(now-windowStart<LEVEL_MS)return false;
        int state=-1;try{if(r!=null)state=r.getRecordingState();}catch(Exception ignored){}
        double rms=windowSamples>0?Math.sqrt(windowSumSq/windowSamples):0,postRms=windowSamples>0?Math.sqrt(windowPostSumSq/windowSamples):0;
        MicGain g=gain;
        android.util.Log.i(MicSource.TAG,"level peak="+windowPeak+" rms="+String.format(java.util.Locale.US,"%.1f",rms)
            +" post_peak="+windowPostPeak+" post_rms="+String.format(java.util.Locale.US,"%.1f",postRms)
            +" gain="+(g==null?"-":String.format(java.util.Locale.US,"%.2f",g.gain()))+" floor="+(g==null?"-":String.format(java.util.Locale.US,"%.1f",g.noiseFloor()))+" ducked_frames="+windowDucked+" source="+(sources==null?"none":MicSource.name(sources[sourceIndex]))
            +" muted="+muted+" playing="+playing+" recState="+state+" mode="+safeMode());
        windowStart=now;windowPeak=0;windowSumSq=0;windowSamples=0;windowPostPeak=0;windowPostSumSq=0;windowDucked=0;
        if(muted||playing||r==null)return false;
        String reason=state!=AudioRecord.RECORDSTATE_RECORDING?"not_recording state="+state:now-lastSoundAt>COLLAPSE_MS?"collapsed ms="+(now-lastSoundAt):null;
        if(reason==null||!restartArmed||restarts>=MAX_RESTARTS)return false;
        restartArmed=false;restarts++;lastSoundAt=now;
        return restart(r,reason);
    }
    /** Recreates the recorder on the same source (else the next one). Capture thread only. */
    private boolean restart(AudioRecord current,String why){
        synchronized(this){
            if(closed.get()||record!=current||sources==null)return false;
            android.util.Log.w(MicSource.TAG,"mic_restart reason="+why+" source="+MicSource.name(sources[sourceIndex])+" count="+restarts);
            int at=sourceIndex;dropMic(current);
            try{return openMic(at);}catch(SecurityException revoked){return false;}
        }
    }
    /** Stops and releases a recorder and its effects. Caller holds this monitor. */
    private void dropMic(AudioRecord current){
        AudioEffect[] old=effects;record=null;effects=new AudioEffect[0];
        try{current.stop();}catch(Exception ignored){}
        try{current.release();}catch(Exception ignored){}
        release(old);
    }
    private static void release(AudioEffect[] list){if(list!=null)for(AudioEffect e:list)if(e!=null)try{e.release();}catch(Exception ignored){}}
    /**
     * The call source gets echo cancelling. Any other source records raw: platform default echo cancelling,
     * noise suppression and gain control are switched off (they over-suppressed speech after the Bot spoke on an OPPO Watch X).
     */
    private AudioEffect[] effectsFor(int source,int session,StringBuilder note){
        java.util.ArrayList<AudioEffect> list=new java.util.ArrayList<>();
        boolean call=source==MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        for(int kind=0;kind<3;kind++){
            if(call&&kind!=0)continue; // The call source keeps its own NS/AGC defaults, as before.
            AudioEffect effect=null;String name=kind==0?"aec":kind==1?"ns":"agc";
            try{
                if(kind==0&&AcousticEchoCanceler.isAvailable())effect=AcousticEchoCanceler.create(session);
                else if(kind==1&&NoiseSuppressor.isAvailable())effect=NoiseSuppressor.create(session);
                else if(kind==2&&AutomaticGainControl.isAvailable())effect=AutomaticGainControl.create(session);
                if(effect==null){note.append(' ').append(name).append("=none");continue;}
                boolean was=effect.getEnabled();effect.setEnabled(call);
                note.append(' ').append(name).append('=').append(was?"on":"off").append("->").append(effect.getEnabled()?"on":"off");
                list.add(effect);
            }catch(RuntimeException failed){if(effect!=null)try{effect.release();}catch(Exception ignored){}note.append(' ').append(name).append("=error");}
        }
        return list.toArray(new AudioEffect[0]);
    }
    private int safeMode(){try{return manager.getMode();}catch(Exception e){return -1;}}
    /**
     * Opens the first source from index that initialises and (unless muted) starts recording. Caller holds
     * this monitor. Returns false when no source is left.
     */
    private boolean openMic(int from){
        int in=Math.max(FRAME*4,AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
        for(int i=from;i<sources.length;i++){
            if(closed.get())return false;
            int source=sources[i];
            if(source==MediaRecorder.AudioSource.VOICE_COMMUNICATION||!media)enterCallMode();else leaveCallMode();
            AudioRecord candidate=null;AudioEffect[] aec=null;StringBuilder note=new StringBuilder();
            try{
                candidate=new AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(in).build();
                if(candidate.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("init");
                aec=effectsFor(source,candidate.getAudioSessionId(),note);
                if(!captureGate.muted()){
                    candidate.startRecording();
                    if(candidate.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("start");
                }
            }catch(SecurityException revoked){
                release(aec);
                if(candidate!=null)try{candidate.release();}catch(Exception ignored){}
                throw revoked;
            }catch(Exception failed){
                android.util.Log.w(MicSource.TAG,"source_failed="+MicSource.name(source)+" cause="+failed.getMessage());
                release(aec);
                if(candidate!=null)try{candidate.release();}catch(Exception ignored){}
                continue;
            }
            record=candidate;effects=aec;sourceIndex=i;
            probing=i+1<sources.length;probeBytes=0;probePeak=0;
            if(closed.get()){record=null;effects=new AudioEffect[0];try{candidate.stop();}catch(Exception ignored){}try{candidate.release();}catch(Exception ignored){}release(aec);return false;}
            android.util.Log.i(MicSource.TAG,"mic_source="+MicSource.name(source)+" effects:"+note+" call_mode="+callMode+" mode="+safeMode()+(probing?" probing":" final"));
            return true;
        }
        return false;
    }
    /** Replaces the current recorder with the next source. Runs on the capture thread. */
    private boolean fallback(AudioRecord current,String why){
        synchronized(this){
            if(closed.get()||record!=current||sources==null||sourceIndex+1>=sources.length)return false;
            android.util.Log.w(MicSource.TAG,"fallback_from="+MicSource.name(sources[sourceIndex])+" reason="+why);
            dropMic(current);
            try{return openMic(sourceIndex+1);}
            catch(SecurityException revoked){return false;}
        }
    }
    /** Watches the first ~1.5 s of unmuted capture; true when it switched to another source. */
    private boolean probe(AudioRecord current,byte[] pcm,int n){
        if(!probing)return false;
        probePeak=Math.max(probePeak,MicSource.peak(pcm,n));probeBytes+=n;
        if(probePeak>MicSource.SILENT_PEAK){probing=false;android.util.Log.i(MicSource.TAG,"mic_ok source="+MicSource.name(sources[sourceIndex])+" peak="+probePeak);return false;}
        if(probeBytes<MicSource.PROBE_BYTES)return false;
        probing=false;
        return fallback(current,"silent peak="+probePeak);
    }
    // The microphone records only when the caller hasn't muted and no other app holds audio focus.
    private boolean userMuted=true,focusPaused;
    synchronized void mute(boolean value){userMuted=value;applyCapture();}
    private synchronized void focusPause(boolean paused){
        if(focusPaused==paused)return;focusPaused=paused;applyCapture();
        AudioTrack t=track;try{if(t!=null){if(paused)t.pause();else t.play();}}catch(Exception ignored){if(!closed.get())listener.failure("Watch audio could not resume.");}
    }
    private void applyCapture(){boolean off=userMuted||focusPaused;if(captureGate.muted()==off)return;captureGate.mute(off);try{AudioRecord r=record;if(r!=null){if(off)r.stop();else r.startRecording();}}catch(Exception ignored){if(!closed.get())listener.failure("Microphone could not change mute state.");}}
    void output(byte[] pcm){if(closed.get()||pcm==null||pcm.length==0)return;
        PcmPlaybackBuffer.Admission admission;int queued;
        synchronized(lock){if(closed.get())return;admission=queue.offer(pcm);queued=queue.size();
            if(admission==PcmPlaybackBuffer.Admission.ACCEPTED){framesQueued+=pcm.length/2;if(!hadOutput)android.util.Log.i(MicSource.TAG,"playback start");hadOutput=true;lock.notifyAll();return;}}
        if(admission==PcmPlaybackBuffer.Admission.CLOSED)return;
        // Counts only: never put audio, captions, account identifiers or credentials in logs.
        android.util.Log.w("GrokWearAudio","playback_reject cause="+admission+" incoming_bytes="+pcm.length+" queued_bytes="+queued+" capacity_bytes="+queue.capacity());
        listener.failure(admission==PcmPlaybackBuffer.Admission.INVALID_FORMAT
                ?"Grok returned an unsupported audio format. Call ended."
                :"Voice reply exceeded the watch's playback buffer. Call ended.");
    }
    private void play(){byte[] scratch=new byte[FRAME];float[] writtenLevel=new float[1];
        PcmPlaybackBuffer.Writer writer=(pcm,offset,length)->{
            // The scratch copy is refilled from the ring on every write, so boosting it in place is safe.
            double voiceGain=softVoiceGain;if(voiceGain>1.0001)boostPcm(pcm,offset,length,voiceGain);
            int n=track.write(pcm,offset,length,AudioTrack.WRITE_NON_BLOCKING);
            if(n>0&&n<=length&&(n&1)==0)writtenLevel[0]=level(pcm,offset,n);
            return n;
        };
        try{while(!closed.get()){
            int n=0;boolean drained=false;
            synchronized(lock){
                if(closed.get()||track==null)return;
                // The same lock covers copying, nonblocking write and interrupt/flush. No old
                // speech remains outside the ring to be written after a caller interrupts it.
                if(queue.size()>0){n=queue.writeTo(writer,scratch);if(n>0)framesWritten+=n/2;}
                else if(hadOutput&&Integer.toUnsignedLong(track.getPlaybackHeadPosition())>=framesWritten){hadOutput=false;drained=true;}
                // Successful writes continue immediately. A full native buffer drains ~1 frame
                // per 42 us, so 20 ms waits keep it topped up; an idle player is woken by
                // output(), interrupt() or close().
                if(n==0&&!drained)lock.wait(hadOutput?20:100);
            }
            if(n<0){listener.failure("Watch speaker playback failed.");return;}
            if(n>0)listener.output(writtenLevel[0]);
            if(drained){android.util.Log.i(MicSource.TAG,"playback drained");listener.output(0);listener.drained();}
        }}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(Exception ignored){if(!closed.get())listener.failure("Watch speaker playback stopped.");}
        finally{Arrays.fill(scratch,(byte)0);}
    }
    boolean isDrained(){synchronized(lock){return !hadOutput&&queue.size()==0;}}
    /** Frames accepted since the last interrupt. Lock-free for UI captions. */
    long queuedFrames(){return framesQueued;}
    /** Frames actually played since the last interrupt. Lock-free for UI captions. */
    long playedFrames(){AudioTrack t=track;if(t==null)return 0;try{return Math.min(framesWritten,Integer.toUnsignedLong(t.getPlaybackHeadPosition()));}catch(RuntimeException released){return 0;}}
    void interrupt(){synchronized(this){
        android.util.Log.i(MicSource.TAG,"playback interrupted");
        synchronized(lock){queue.clear();framesWritten=0;framesQueued=0;hadOutput=false;
        // Flushing restarts the track, except while another app holds audio focus: focus coming back plays it.
        if(track!=null)try{track.pause();track.flush();if(!focusPaused)track.play();}catch(Exception ignored){}lock.notifyAll();}}listener.output(0);}
    static float level(byte[] pcm){return level(pcm,0,pcm.length);}
    private static float level(byte[] pcm,int offset,int length){double sum=0;for(int i=offset;i+1<offset+length;i+=2){short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));sum+=(double)sample*sample;}return length<2?0:(float)Math.min(1,Math.sqrt(sum/(length/2))/10000.0);}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;captureGate.mute(true);
        AudioRecord r=record;if(r!=null)try{r.stop();}catch(Exception ignored){}
        if(capture!=null)capture.interrupt();if(playback!=null)playback.interrupt();
        synchronized(lock){queue.close();framesWritten=0;framesQueued=0;hadOutput=false;lock.notifyAll();
            LoudnessEnhancer enhancer=loudness;loudness=null;if(enhancer!=null)try{enhancer.setEnabled(false);enhancer.release();}catch(Exception ignored){}
            if(track!=null){try{track.pause();track.flush();track.release();}catch(Exception ignored){}track=null;}}
        // The capture read is unblocked by stop before release.
        if(capture!=null&&capture!=Thread.currentThread())try{capture.join(250);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        if(r!=null)try{r.release();}catch(Exception ignored){}record=null;
        release(effects);effects=new AudioEffect[0];
        synchronized(this){leaveCallMode();}
        // Belt and braces: whatever happened above, never leave the watch in call mode we set.
        if(changedMode)try{if(manager.getMode()==AudioManager.MODE_IN_COMMUNICATION)manager.setMode(oldMode);changedMode=false;}catch(Exception ignored){}
        if(focusHeld)try{manager.abandonAudioFocusRequest(focus);}catch(Exception ignored){}
    }
}
