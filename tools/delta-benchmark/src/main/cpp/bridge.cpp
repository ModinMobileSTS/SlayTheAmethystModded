#include <jni.h>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <stdexcept>
#include <algorithm>
#include "libHDiffPatch/HDiff/diff.h"
#include "libHDiffPatch/HPatch/patch.h"
extern "C" {
// Upstream C headers use C++ keyword 'new' as parameter name. Declare identical
// callback ABI here without modifying pinned upstream sources.
struct bsdiff_stream {
    void* opaque;
    void* (*malloc)(size_t);
    void (*free)(void*);
    int (*write)(bsdiff_stream*,const void*,int);
};
struct bspatch_stream { void* opaque; int (*read)(const bspatch_stream*,void*,int); };
int bsdiff(const uint8_t*,int64_t,const uint8_t*,int64_t,bsdiff_stream*);
int bspatch(const uint8_t*,int64_t,uint8_t*,int64_t,bspatch_stream*);
#include "xdelta3.h"
}

using Bytes = std::vector<unsigned char>;
static constexpr size_t INPUT_LIMIT = 16 * 1024 * 1024 + 37;
static constexpr size_t MAX_PATCH = 64 * 1024 * 1024;
static Bytes bytes(JNIEnv* env, jbyteArray value, size_t limit) {
    size_t size = env->GetArrayLength(value);
    if (size > limit) throw std::runtime_error("Native smoke input limit");
    Bytes out(size + 1); // non-null backing for empty arrays, logical size restored below
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(out.data()));
    out.resize(size);
    return out;
}
static int writeDiff(bsdiff_stream* stream, const void* data, int size) {
    auto& out = *static_cast<Bytes*>(stream->opaque);
    if (size < 0 || static_cast<size_t>(size) > MAX_PATCH - out.size()) return -1;
    auto start = static_cast<const unsigned char*>(data);
    out.insert(out.end(), start, start + size);
    return 0;
}
struct Reader { const Bytes& bytes; size_t pos = 0; };
static int readDiff(const bspatch_stream* stream, void* data, int size) {
    auto& reader = *static_cast<Reader*>(stream->opaque);
    if (size < 0 || static_cast<size_t>(size) > reader.bytes.size() - reader.pos) return -1;
    memcpy(data, reader.bytes.data() + reader.pos, size);
    reader.pos += size;
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_stamethyst_deltabench_NativeBridge_transform(JNIEnv* env, jclass, jint engine,
        jboolean encode, jbyteArray baseArray, jbyteArray inputArray, jint expected) {
    try {
        Bytes base = bytes(env, baseArray, INPUT_LIMIT);
        Bytes input = bytes(env, inputArray, encode ? INPUT_LIMIT : MAX_PATCH);
        Bytes out;
        if (expected < 0 || static_cast<size_t>(expected) > INPUT_LIMIT) throw std::runtime_error("Output limit");
        if (encode) {
            if (engine == 0) {
                // Match JVM's ENDSLEY header; body is the mendsley callback format.
                const char* signature = "ENDSLEY/BSDIFF43";
                out.insert(out.end(), signature, signature + 16);
                uint64_t size = input.size();
                for (int i = 0; i < 8; i++) out.push_back((size >> (8*i)) & 255);
                bsdiff_stream stream{&out, malloc, free, writeDiff};
                if (bsdiff(base.data(), base.size(), input.data(), input.size(), &stream)) throw std::runtime_error("bsdiff failed");
            } else if (engine == 1) {
                create_single_compressed_diff(input.data(), input.data()+input.size(), base.data(), base.data()+base.size(),
                    out, nullptr, 256*1024, 4, false, 1);
            } else if (engine == 2) {
                // Bound to this input rather than touching a fixed 64 MiB buffer
                // on every tiny encode. Allow ample VCDIFF instruction overhead.
                out.resize(std::min(MAX_PATCH, input.size()*2 + 65536));
                usize_t size = 0;
                int rc = xd3_encode_memory(input.data(), input.size(), base.data(), base.size(),
                    out.data(), &size, out.size(), XD3_COMPLEVEL_6);
                if (rc) throw std::runtime_error("xdelta encode failed: " + std::to_string(rc));
                out.resize(size);
            } else throw std::runtime_error("Unknown native engine");
        } else {
            out.resize(static_cast<size_t>(expected) + 1);
            out.resize(expected);
            if (engine == 0) {
                if (input.size() < 24 || memcmp(input.data(), "ENDSLEY/BSDIFF43", 16)) throw std::runtime_error("Invalid BSDiff header");
                uint64_t size = 0;
                for (int i=0; i<8; i++) size |= uint64_t(input[16+i]) << (8*i);
                if (size != static_cast<uint64_t>(expected)) throw std::runtime_error("BSDiff target mismatch");
                Reader reader{input,24};
                bspatch_stream stream{&reader,readDiff};
                if (bspatch(base.data(),base.size(),out.data(),out.size(),&stream) || reader.pos != input.size()) throw std::runtime_error("bspatch failed");
            } else if (engine == 1) {
                hpatch_singleCompressedDiffInfo info{};
                if (!getSingleCompressedDiffInfo_mem(&info,input.data(),input.data()+input.size())
                    || info.newDataSize != out.size() || info.oldDataSize != base.size()
                    || info.stepMemSize > MAX_PATCH) throw std::runtime_error("Invalid HDiff info");
                hpatch_TStreamOutput output;
                hpatch_TStreamInput old, diff;
                mem_as_hStreamOutput(&output,out.data(),out.data()+out.size());
                mem_as_hStreamInput(&old,base.data(),base.data()+base.size());
                mem_as_hStreamInput(&diff,input.data(),input.data()+input.size());
                Bytes cache(info.stepMemSize + hpatch_kStreamCacheSize*3);
                if (!patch_single_compressed_diff(&output,&old,&diff,info.diffDataPos,info.uncompressedSize,
                    info.compressedSize,nullptr,info.coverCount,info.stepMemSize,cache.data(),cache.data()+cache.size(),nullptr,1)) throw std::runtime_error("hpatch failed");
            } else if (engine == 2) {
                usize_t size = 0;
                int rc = xd3_decode_memory(input.data(),input.size(),base.data(),base.size(),out.data(),&size,out.size(),0);
                if (rc || size != out.size()) throw std::runtime_error("xdelta decode failed: " + std::to_string(rc));
            } else throw std::runtime_error("Unknown native engine");
        }
        if (out.size() > MAX_PATCH) throw std::runtime_error("Patch limit");
        jbyteArray result = env->NewByteArray(out.size());
        if (result) env->SetByteArrayRegion(result,0,out.size(),reinterpret_cast<const jbyte*>(out.data()));
        return result;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/io/IOException"),error.what());
        return nullptr;
    }
}
