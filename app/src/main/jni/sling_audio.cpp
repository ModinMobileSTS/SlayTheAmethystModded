// AAudio output for SlingBreak's offline-rendered voice samples.
// JNI/control operations are serialized; the realtime callback never allocates or locks.
#include <aaudio/AAudio.h>
#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kSamples = 256, kVoices = 64, kBatch = 12, kQueue = 64;
constexpr float kPi = 3.14159265358979323846f;
struct Part { int id=0, delay=0; float left=0, right=0; bool wet=false; };
struct Command {
    std::array<Part,kBatch> parts{};
    int count=0, type=0; bool priority=false;
    uint32_t generation=0;
};
struct Voice {
    const std::vector<float>* pcm=nullptr;
    int position=0, delay=0, type=0;
    float left=0,right=0; bool wet=false,priority=false;
    uint64_t order=0;
};
struct Engine {
    AAudioStream* stream=nullptr;
    int rate=0,burst=0,buffer=0,capacity=0,mode=0,sharing=0,lastXruns=0;
    bool committed=false,active=true;
    std::atomic<bool> muted{true},failed{false};
    std::atomic<int> error{0},voiceCount{0},dropped{0};
    std::atomic<uint32_t> generation{1},write{0},read{0};
    std::array<Command,kQueue> queue{};
    std::array<std::vector<float>,kSamples> samples;
    std::array<Voice,kVoices> voices{};
    std::vector<float> echoL,echoR;
    std::array<float,2048> curve{};
    uint32_t seenGeneration=0;
    uint64_t order=0;
    size_t echoPosition=0;
    size_t totalSamples=0;
    float gain=0,gainStep=0;

    ~Engine(){ if(stream){AAudioStream_requestStop(stream);AAudioStream_close(stream);} }
    static void onError(AAudioStream*,void* data,aaudio_result_t code){
        auto* e=static_cast<Engine*>(data);
        e->error.store(code);e->failed.store(true);
        // Closing here can deadlock: the control thread owns destruction.
    }
    static aaudio_data_callback_result_t callback(AAudioStream*,void* data,void* output,int32_t frames){
        return static_cast<Engine*>(data)->render(static_cast<float*>(output),frames);
    }
    bool open(){
        // Try exclusive first; keep the shared path for devices that reject it.
        for(auto share:{AAUDIO_SHARING_MODE_EXCLUSIVE,AAUDIO_SHARING_MODE_SHARED}){
            AAudioStreamBuilder* builder=nullptr;
            if(AAudio_createStreamBuilder(&builder)!=AAUDIO_OK)return false;
            AAudioStreamBuilder_setDirection(builder,AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setFormat(builder,AAUDIO_FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setChannelCount(builder,2);
            AAudioStreamBuilder_setSharingMode(builder,share);
            AAudioStreamBuilder_setPerformanceMode(builder,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            // Optional newer setters: keep this library loadable on API 26.
            using AttributeSetter=void (*)(AAudioStreamBuilder*,int32_t);
            const auto setUsage=reinterpret_cast<AttributeSetter>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setUsage"));
            const auto setContent=reinterpret_cast<AttributeSetter>(dlsym(RTLD_DEFAULT,"AAudioStreamBuilder_setContentType"));
            if(setUsage)setUsage(builder,AAUDIO_USAGE_GAME);
            if(setContent)setContent(builder,AAUDIO_CONTENT_TYPE_SONIFICATION);
            AAudioStreamBuilder_setDataCallback(builder,callback,this);
            AAudioStreamBuilder_setErrorCallback(builder,onError,this);
            const auto result=AAudioStreamBuilder_openStream(builder,&stream);
            AAudioStreamBuilder_delete(builder);
            if(result==AAUDIO_OK)break;
            stream=nullptr;
        }
        if(!stream)return false;
        rate=AAudioStream_getSampleRate(stream);
        burst=AAudioStream_getFramesPerBurst(stream);
        capacity=AAudioStream_getBufferCapacityInFrames(stream);
        mode=AAudioStream_getPerformanceMode(stream);
        sharing=AAudioStream_getSharingMode(stream);
        if(rate<8000||rate>192000||burst<=0||capacity<=0||AAudioStream_getChannelCount(stream)!=2||
           AAudioStream_getFormat(stream)!=AAUDIO_FORMAT_PCM_FLOAT)return false;
        AAudioStream_setBufferSizeInFrames(stream,std::min(capacity,burst*2));
        buffer=AAudioStream_getBufferSizeInFrames(stream);
        // Do not replace Web Audio with another known-large-buffer path.
        if(buffer<=0||static_cast<double>(buffer)/rate>=.05)return false;
        echoL.resize(static_cast<size_t>(std::round(rate*.105)),0);
        echoR.resize(echoL.size(),0);
        for(size_t i=0;i<curve.size();++i)curve[i]=.9f*std::tanh((float(i)/(curve.size()-1)*2-1)*1.4f);
        gainStep=1-std::exp(-1.f/(rate*.012f));
        return true;
    }
    bool waitFor(aaudio_stream_state_t wanted){
        for(int i=0;i<10&&!failed.load();++i){
            auto state=AAudioStream_getState(stream);
            if(state==wanted)return true;
            if(state==AAUDIO_STREAM_STATE_DISCONNECTED)return false;
            aaudio_stream_state_t next;
            AAudioStream_waitForStateChange(stream,state,&next,10000000);
        }
        return false;
    }
    bool start(){
        if(failed.load()||!active)return false;
        if(AAudioStream_requestStart(stream)!=AAUDIO_OK||!waitFor(AAUDIO_STREAM_STATE_STARTED)){
            failed.store(true);return false;
        }
        return true;
    }
    bool setActive(bool value){
        if(active==value)return !failed.load();
        active=value;muted.store(true);generation.fetch_add(1);
        if(!committed)return !failed.load();
        if(value)return start();
        if(AAudioStream_requestPause(stream)!=AAUDIO_OK||!waitFor(AAUDIO_STREAM_STATE_PAUSED)||
           AAudioStream_requestFlush(stream)!=AAUDIO_OK||!waitFor(AAUDIO_STREAM_STATE_FLUSHED)){
            failed.store(true);return false;
        }
        return true;
    }
    float limit(float value) const {
        const float index=(std::clamp(value,-1.f,1.f)+1)*.5f*(curve.size()-1);
        const size_t lo=static_cast<size_t>(index),hi=std::min(lo+1,curve.size()-1);
        return curve[lo]+(curve[hi]-curve[lo])*(index-lo);
    }
    int count()const{int n=0;for(const auto& v:voices)if(v.pcm)++n;return n;}
    void clear(){
        for(auto& v:voices)v.pcm=nullptr;
        std::fill(echoL.begin(),echoL.end(),0);std::fill(echoR.begin(),echoR.end(),0);
        gain=0;
    }
    void consume(const Command& c){
        int n=count();
        if(!c.priority&&n>28&&c.type!=10&&c.type!=11){dropped.fetch_add(1);return;}
        if(c.priority&&n>36){
            while(n>36){
                Voice* victim=nullptr;int best=4;
                for(auto& v:voices)if(v.pcm){
                    const int score=v.priority?(v.type==c.type?1:2):0;
                    if(!victim||score<best||(score==best&&v.order<victim->order)){victim=&v;best=score;}
                }
                victim->pcm=nullptr;--n;
            }
        }
        if(n+c.count>kVoices){dropped.fetch_add(1);return;}
        for(int i=0;i<c.count;++i)for(auto& v:voices)if(!v.pcm){
            const auto& p=c.parts[i];v={&samples[p.id],0,p.delay,c.type,p.left,p.right,p.wet,c.priority,++order};break;
        }
    }
    aaudio_data_callback_result_t render(float* out,int frames){
        const auto gen=generation.load();
        if(gen!=seenGeneration){clear();seenGeneration=gen;}
        const bool silent=muted.load()||failed.load();
        auto r=read.load(std::memory_order_relaxed);
        const auto w=write.load(std::memory_order_acquire);
        while(r!=w){const auto& c=queue[r%kQueue];if(!silent&&c.generation==gen)consume(c);++r;}
        read.store(r,std::memory_order_release);
        const float target=silent?0:.7f;
        for(int f=0;f<frames;++f){
            float l=0,rgt=0,sendL=0,sendR=0;
            for(auto& v:voices)if(v.pcm){
                if(v.delay>0){--v.delay;continue;}
                if(v.position>=static_cast<int>(v.pcm->size())){v.pcm=nullptr;continue;}
                const float sample=(*v.pcm)[v.position++],sl=sample*v.left,sr=sample*v.right;
                l+=sl;rgt+=sr;if(v.wet){sendL+=sl;sendR+=sr;}
            }
            const float el=echoL[echoPosition],er=echoR[echoPosition];
            echoL[echoPosition]=sendL+.18f*el;echoR[echoPosition]=sendR+.18f*er;
            if(++echoPosition==echoL.size())echoPosition=0;
            gain+=(target-gain)*gainStep;
            out[f*2]=limit((l+.16f*el)*gain);out[f*2+1]=limit((rgt+.16f*er)*gain);
        }
        voiceCount.store(count());
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
};
std::mutex control;
std::unordered_map<jlong,std::unique_ptr<Engine>> engines;
jlong nextId=1;
Engine* get(jlong id){auto i=engines.find(id);return i==engines.end()?nullptr:i->second.get();}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeCreate(JNIEnv*,jobject){
    std::lock_guard<std::mutex> lock(control);
    try{auto engine=std::make_unique<Engine>();if(!engine->open())return 0;
        const auto id=nextId++;engines.emplace(id,std::move(engine));return id;
    }catch(...){return 0;}
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeInfo(JNIEnv* env,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);const auto* e=get(id);
    if(!e)return env->NewStringUTF("{\"error\":\"no-stream\"}");
    char json[768];
    std::snprintf(json,sizeof(json),"{\"version\":1,\"session\":%lld,\"sampleRate\":%d,\"framesPerBurst\":%d,\"bufferFrames\":%d,\"capacityFrames\":%d,\"bufferDurationMs\":%.3f,\"performanceMode\":%d,\"sharingMode\":%d,\"streamState\":%d,\"error\":%d,\"failed\":%s,\"activeVoices\":%d,\"dropped\":%d,\"xruns\":%d}",
        static_cast<long long>(id),e->rate,e->burst,e->buffer,e->capacity,1000.0*e->buffer/e->rate,e->mode,e->sharing,
        AAudioStream_getState(e->stream),e->error.load(),e->failed.load()?"true":"false",e->voiceCount.load(),e->dropped.load(),AAudioStream_getXRunCount(e->stream));
    return env->NewStringUTF(json);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeUpload(JNIEnv* env,jobject,jlong id,jint sample,jfloatArray pcm){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->committed||sample<0||sample>=kSamples||!pcm)return false;
    const int size=env->GetArrayLength(pcm);
    if(size<1||size>e->rate*2||!e->samples[sample].empty()||e->totalSamples+size>4000000)return false;
    try{
        std::vector<float> data(size);env->GetFloatArrayRegion(pcm,0,size,data.data());
        if(env->ExceptionCheck())return false;
        for(float v:data)if(!std::isfinite(v)||std::abs(v)>4)return false;
        e->samples[sample]=std::move(data);e->totalSamples+=size;return true;
    }catch(...){return false;}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeCommit(JNIEnv*,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||e->committed||!e->totalSamples)return false;
    e->committed=true;return e->start();
}
extern "C" JNIEXPORT jint JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativePlay(JNIEnv* env,jobject,jlong id,jint type,jboolean priority,jintArray ids,jfloatArray params){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||!e->committed||e->failed.load())return -1;
    if(e->muted.load()||!e->active)return 0;
    if(!ids||!params)return -2;
    const int n=env->GetArrayLength(ids);
    if(n<1||n>kBatch||env->GetArrayLength(params)!=n*3)return -2;
    std::array<jint,kBatch> indexes{};std::array<jfloat,kBatch*3> values{};
    env->GetIntArrayRegion(ids,0,n,indexes.data());env->GetFloatArrayRegion(params,0,n*3,values.data());
    if(env->ExceptionCheck())return -2;
    Command c;c.count=n;c.type=type;c.priority=priority;c.generation=e->generation.load();
    for(int i=0;i<n;++i){
        const int sample=indexes[i];const float delay=values[i*3],pan=values[i*3+1],wet=values[i*3+2];
        if(sample<0||sample>=kSamples||e->samples[sample].empty()||!std::isfinite(delay)||delay<0||delay>2||!std::isfinite(pan)||std::abs(pan)>1||!std::isfinite(wet))return -2;
        const float angle=(pan+1)*kPi/4;
        c.parts[i]={sample,static_cast<int>(std::round(delay*e->rate)),std::cos(angle),std::sin(angle),wet>0};
    }
    const auto w=e->write.load(std::memory_order_relaxed),r=e->read.load(std::memory_order_acquire);
    if(w-r>=kQueue){e->dropped.fetch_add(1);return 0;}
    e->queue[w%kQueue]=c;e->write.store(w+1,std::memory_order_release);return 1;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeMute(JNIEnv*,jobject,jlong id,jboolean muted){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);if(!e||e->failed.load())return false;
    if(e->muted.exchange(muted)&&muted)return true;
    if(muted)e->generation.fetch_add(1);
    return true;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeActive(JNIEnv*,jobject,jlong id,jboolean active){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);return e&&e->setActive(active);
}
extern "C" JNIEXPORT void JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeMaintain(JNIEnv*,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);auto* e=get(id);
    if(!e||!e->committed||!e->active||e->failed.load())return;
    const int xruns=AAudioStream_getXRunCount(e->stream);
    if(xruns>e->lastXruns){
        const int target=std::min(e->capacity,e->buffer+e->burst);
        // Bounded adaptation: prefer stable Web Audio over repeated underruns.
        if(target<=e->buffer||static_cast<double>(target)/e->rate>=.05){
            e->failed.store(true);e->error.store(AAUDIO_ERROR_UNAVAILABLE);
        }else{
            const auto result=AAudioStream_setBufferSizeInFrames(e->stream,target);
            if(result<=0||static_cast<double>(result)/e->rate>=.05){
                e->failed.store(true);e->error.store(result<0?result:AAUDIO_ERROR_UNAVAILABLE);
            }else e->buffer=result;
        }
    }
    e->lastXruns=xruns;
}
extern "C" JNIEXPORT void JNICALL
Java_io_stamethyst_backend_audio_SlingNativeAudioBridge_nativeRelease(JNIEnv*,jobject,jlong id){
    std::lock_guard<std::mutex> lock(control);engines.erase(id);
}
