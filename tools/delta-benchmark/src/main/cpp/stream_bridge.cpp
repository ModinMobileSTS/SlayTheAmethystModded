#include <jni.h>
#include <cstdio>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <cstdlib>
#include <limits>
#include <list>
#include <unistd.h>
#include "libHDiffPatch/HDiff/diff.h"
#include "libHDiffPatch/HPatch/patch.h"
extern "C" {
#include "xdelta3.h"
}

namespace {
constexpr uint64_t FILE_LIMIT=128ULL*1024*1024;
// xdelta exposes allocator callbacks. This caps its own allocations only, not libc,
// JNI, stdio buffers, the input/source buffers or whole-process RSS.
struct Budget {
    size_t live=0,peak=0,limit,rejected=0;
    struct alignas(std::max_align_t) Header { size_t size; };
    static void* alloc(void* opaque,size_t count,size_t size){
        auto& b=*static_cast<Budget*>(opaque);
        if(size && count>std::numeric_limits<size_t>::max()/size) return nullptr;
        size_t n=count*size;
        if(n>b.limit-b.live || n>std::numeric_limits<size_t>::max()-sizeof(Header)) {b.rejected=n;return nullptr;}
        auto* p=static_cast<Header*>(malloc(sizeof(Header)+n)); if(!p)return nullptr;
        p->size=n; b.live+=n; b.peak=std::max(b.peak,b.live); return p+1;
    }
    static void release(void* opaque,void* ptr){
        if(!ptr)return;
        auto& b=*static_cast<Budget*>(opaque); auto* p=static_cast<Header*>(ptr)-1;
        b.live-=p->size; free(p);
    }
};
struct File {
    FILE* f;
    uint64_t size=0, readBytes=0, writeBytes=0;
    File(const char* path,const char* mode):f(fopen(path,mode)) {
        if(!f) throw std::runtime_error("Open failed");
        fseeko(f,0,SEEK_END); size=ftello(f); rewind(f);
        if(size>FILE_LIMIT) { fclose(f); throw std::runtime_error("File limit"); }
    }
    ~File(){fclose(f);}
    void sync(){if(fflush(f)||fsync(fileno(f))) throw std::runtime_error("Sync failed");}
};
hpatch_BOOL readH(const hpatch_TStreamInput* s,hpatch_StreamPos_t pos,unsigned char* b,unsigned char* end){
    auto& file=*static_cast<File*>(s->streamImport); size_t n=end-b;
    if(pos>file.size || n>file.size-pos || fseeko(file.f,pos,SEEK_SET)) return false;
    size_t got=fread(b,1,n,file.f); file.readBytes+=got; return got==n;
}
hpatch_BOOL writeH(const hpatch_TStreamOutput* s,hpatch_StreamPos_t pos,const unsigned char* b,const unsigned char* end){
    auto& file=*static_cast<File*>(s->streamImport); size_t n=end-b;
    if(pos>s->streamSize || n>s->streamSize-pos || fseeko(file.f,pos,SEEK_SET)) return false;
    size_t got=fwrite(b,1,n,file.f); file.writeBytes+=got; file.size=std::max<uint64_t>(file.size,pos+got); return got==n;
}
void xdelta(bool encode,File& base,File& input,File& out,size_t window,uint64_t limit,Budget& budget,uint64_t sourceWindow){
    xd3_stream stream{}; xd3_config config; xd3_source source{};
    xd3_init_config(&config,XD3_COMPLEVEL_6|XD3_ADLER32);
    config.winsize=window;
    config.alloc=Budget::alloc; config.freef=Budget::release; config.opaque=&budget;
    if(xd3_config_stream(&stream,&config)) throw std::runtime_error("xdelta config failed");
    struct Guard { xd3_stream* s; ~Guard(){xd3_free_stream(s);} } guard{&stream};
    // Keep source blocks across requests: the encoder may alternate between them
    // for each match probe. One scratch block turns that into pathological rereads.
    struct Block { uint64_t number; size_t valid; std::vector<uint8_t> data; };
    std::list<Block> blocks;
    size_t cacheBytes=0;
    constexpr size_t CACHE_LIMIT=8*1024*1024;
    auto load=[&](uint64_t number)->Block& {
        for(auto it=blocks.begin();it!=blocks.end();++it) if(it->number==number){
            blocks.splice(blocks.begin(),blocks,it);return blocks.front();
        }
        if(number>base.size/window) throw std::runtime_error("Source block outside file");
        if(cacheBytes+window>CACHE_LIMIT){blocks.pop_back();cacheBytes-=window;}
        blocks.push_front(Block{number,0,std::vector<uint8_t>(window)});cacheBytes+=window;
        auto& block=blocks.front();
        if(fseeko(base.f,number*window,SEEK_SET))throw std::runtime_error("Source seek failed");
        block.valid=fread(block.data.data(),1,window,base.f);base.readBytes+=block.valid;
        if(ferror(base.f))throw std::runtime_error("Source read failed");
        return block;
    };
    std::vector<uint8_t> buf(window);
    auto& first=load(0);
    source.blksize=window; source.curblk=first.data.data(); source.max_winsize=sourceWindow;
    source.onblk=first.valid;
    if(xd3_set_source_and_size(&stream,&source,base.size)) throw std::runtime_error("xdelta source failed");
    bool eof=false;
    while(true){
        int rc=encode?xd3_encode_input(&stream):xd3_decode_input(&stream);
        if(rc==XD3_INPUT){
            if(eof) break;
            size_t n=fread(buf.data(),1,buf.size(),input.f); input.readBytes+=n;
            if(ferror(input.f)) throw std::runtime_error("Input read failed");
            eof=n<buf.size();
            if(eof) xd3_set_flags(&stream,stream.flags|XD3_FLUSH);
            xd3_avail_input(&stream,buf.data(),n);
        }else if(rc==XD3_OUTPUT){
            if(stream.avail_out>limit-out.size) throw std::runtime_error("Output limit");
            size_t n=fwrite(stream.next_out,1,stream.avail_out,out.f);
            if(n!=stream.avail_out) throw std::runtime_error("Output write failed");
            out.size+=n; out.writeBytes+=n; xd3_consume_output(&stream);
        }else if(rc==XD3_GETSRCBLK){
            auto& block=load(source.getblkno);
            source.onblk=block.valid;source.curblk=block.data.data();
            source.curblkno=source.getblkno;
        }else if(rc!=XD3_GOTHEADER && rc!=XD3_WINSTART && rc!=XD3_WINFINISH){
            throw std::runtime_error(std::string("xdelta stream error: ")+(stream.msg?stream.msg:"unknown")
                +"; allocPeak="+std::to_string(budget.peak)+"; rejectedRequest="+std::to_string(budget.rejected)
                +"; allocLimit="+std::to_string(budget.limit));
        }
    }
    if(xd3_close_stream(&stream)) throw std::runtime_error("xdelta incomplete stream");
}
struct Utf {
    JNIEnv* env; jstring value; const char* text;
    Utf(JNIEnv* e,jstring v):env(e),value(v),text(e->GetStringUTFChars(v,nullptr)){}
    ~Utf(){if(text)env->ReleaseStringUTFChars(value,text);}
};
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_stamethyst_deltabench_NativeBridge_stream(JNIEnv* env,jclass,jboolean hd,jboolean encode,
        jstring basePath,jstring inputPath,jstring outPath,jlong expected,jint window,jlong outputLimit,jlong allocLimit,jlong sourceWindow){
    try{
        if(window<65536 || window>4*1024*1024 || (window&(window-1))) throw std::runtime_error("Window limit");
        if(outputLimit<0 || uint64_t(outputLimit)>FILE_LIMIT || expected<0 || uint64_t(expected)>FILE_LIMIT) throw std::runtime_error("Size limit");
        Utf bp(env,basePath),ip(env,inputPath),op(env,outPath);
        File base(bp.text,"rb"),input(ip.text,"rb"),out(op.text,"w+b");
        uint64_t limit=encode?outputLimit:std::min(expected,outputLimit);
        if(allocLimit<0 || uint64_t(allocLimit)>256ULL*1024*1024) throw std::runtime_error("Allocation limit range");
        if(sourceWindow<window || uint64_t(sourceWindow)>FILE_LIMIT) throw std::runtime_error("Source search window limit");
        Budget budget{0,0,static_cast<size_t>(allocLimit)};
        if(hd){
            hpatch_TStreamInput old{&base,base.size,readH,nullptr},in{&input,input.size,readH,nullptr};
            hpatch_TStreamOutput dest{&out,limit,nullptr,writeH};
            if(encode) create_single_compressed_diff_stream(&in,&old,&dest,nullptr,window,64,nullptr);
            else{
                hpatch_singleCompressedDiffInfo info{};
                if(!getSingleCompressedDiffInfo(&info,&in,0)||info.newDataSize!=uint64_t(expected)
                    || info.oldDataSize!=base.size || info.stepMemSize>uint64_t(window)) throw std::runtime_error("HDiff header/cache limit");
                std::vector<unsigned char> cache(info.stepMemSize+3*hpatch_kStreamCacheSize);
                if(!patch_single_compressed_diff(&dest,&old,&in,info.diffDataPos,info.uncompressedSize,
                    info.compressedSize,nullptr,info.coverCount,info.stepMemSize,cache.data(),cache.data()+cache.size(),nullptr,1)) throw std::runtime_error("HDiff stream apply failed");
            }
        }else xdelta(encode,base,input,out,window,limit,budget,sourceWindow);
        if(!encode && out.size!=uint64_t(expected)) throw std::runtime_error("Output size mismatch");
        out.sync();
        jlong stats[]={jlong(base.readBytes+input.readBytes),jlong(out.writeBytes),jlong(out.size),hd?-1:jlong(budget.peak)};
        jlongArray result=env->NewLongArray(4); if(result)env->SetLongArrayRegion(result,0,4,stats); return result;
    }catch(const std::exception& e){env->ThrowNew(env->FindClass("java/io/IOException"),e.what());return nullptr;}
}
