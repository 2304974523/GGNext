#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/search_engine.hpp"
#include <memory>
#include <mutex>
#include <cstring>
#include <fstream>
#include <algorithm>

// Support compilation under both Android NDK (<jni.h>) and host Linux CI builds without JDK headers
#if __has_include(<jni.h>)
#include <jni.h>
#else
using jint      = int32_t;
using jlong     = int64_t;
using jboolean  = uint8_t;
using jfloat    = float;
using jdouble   = double;
using jbyte     = int8_t;
using jsize     = int32_t;
struct _jobject;
using jobject       = _jobject*;
using jclass        = _jobject*;
using jstring       = _jobject*;
using jintArray     = _jobject*;
using jlongArray    = _jobject*;
using jdoubleArray  = _jobject*;
using jbyteArray    = _jobject*;
using jobjectArray  = _jobject*;
struct JNIEnv {
    const char* GetStringUTFChars(jstring, jboolean*) { return ""; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jstring NewStringUTF(const char*) { return nullptr; }
    jsize GetArrayLength(jobject) { return 0; }
    jbyte* GetByteArrayElements(jbyteArray, jboolean*) { return nullptr; }
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}
    jint* GetIntArrayElements(jintArray, jboolean*) { return nullptr; }
    void ReleaseIntArrayElements(jintArray, jint*, jint) {}
    jlong* GetLongArrayElements(jlongArray, jboolean*) { return nullptr; }
    void ReleaseLongArrayElements(jlongArray, jlong*, jint) {}
    jdouble* GetDoubleArrayElements(jdoubleArray, jboolean*) { return nullptr; }
    void ReleaseDoubleArrayElements(jdoubleArray, jdouble*, jint) {}
    jbyteArray NewByteArray(jsize) { return nullptr; }
    void SetByteArrayRegion(jbyteArray, jsize, jsize, const jbyte*) {}
    jlongArray NewLongArray(jsize) { return nullptr; }
    void SetLongArrayRegion(jlongArray, jsize, jsize, const jlong*) {}
};
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#endif

namespace {

struct NativeEngineSession {
    std::mutex                                mtx;
    mem_engine::ProcIO                        io;
    std::unique_ptr<mem_engine::SearchEngine> scanner;
    std::unique_ptr<mem_engine::ValueLocker>  locker;
    mem_engine::CancellationToken             cancelToken;
    std::vector<mem_engine::VmaRegion>        cachedAllRegions;

    explicit NativeEngineSession(size_t threads, const std::string& spillDir)
        : scanner(std::make_unique<mem_engine::SearchEngine>(io, threads, spillDir)),
          locker(std::make_unique<mem_engine::ValueLocker>(io, 5)) {}

    void refreshAllRegions() {
        if (io.pid() > 0) {
            cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(io.pid(), 0, false);
        } else {
            cachedAllRegions.clear();
        }
    }
};

constexpr uint32_t kGGNextRegionOrder[15] = {
    mem_engine::RegionFlag::CH,
    mem_engine::RegionFlag::JH,
    mem_engine::RegionFlag::CA,
    mem_engine::RegionFlag::CD,
    mem_engine::RegionFlag::CB,
    mem_engine::RegionFlag::PS,
    mem_engine::RegionFlag::A,
    mem_engine::RegionFlag::J,
    mem_engine::RegionFlag::S,
    mem_engine::RegionFlag::AS,
    mem_engine::RegionFlag::V,
    mem_engine::RegionFlag::O,
    mem_engine::RegionFlag::B,
    mem_engine::RegionFlag::XA,
    mem_engine::RegionFlag::XS
};

} // anonymous namespace

extern "C" {

// ============================================================================
// 1. Standard QA MemDiagnosticEngine JNI Endpoints
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeCreate(
    JNIEnv* env,
    jobject /*thiz*/,
    jint workerThreads,
    jstring spillDirJstr
) {
    std::string spillDir = "/data/local/tmp";
    if (spillDirJstr != nullptr) {
        const char* cstr = env->GetStringUTFChars(spillDirJstr, nullptr);
        if (cstr && cstr[0] != '\0') spillDir = cstr;
        if (cstr) env->ReleaseStringUTFChars(spillDirJstr, cstr);
    }
    auto* session = new NativeEngineSession(static_cast<size_t>(workerThreads > 0 ? workerThreads : 4), spillDir);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (session) {
        session->cancelToken.cancel();
        delete session;
    }
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeAttachProcess(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint pid
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAll();
    auto rc = session->io.attach(static_cast<int>(pid));
    if (rc == mem_engine::EngineErrc::OK) {
        session->scanner->refreshRegions();
        session->refreshAllRegions();
    }
    return static_cast<jint>(rc);
}

JNIEXPORT void JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeSetRegionMask(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint regionMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->setRegionMask(static_cast<uint32_t>(regionMask));
    if (session->io.pid() > 0) {
        session->scanner->refreshRegions();
    }
}

JNIEXPORT void JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeCancelScan(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (session) {
        session->cancelToken.cancel();
    }
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeSearchExact(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchExact(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeRefineExact(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineExact(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeSearchRange(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint dataType,
    jlong intMin,
    jlong intMax,
    jdouble floatMin,
    jdouble floatMax,
    jboolean inclusive,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchRange(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intMin),
        static_cast<int64_t>(intMax),
        static_cast<double>(floatMin),
        static_cast<double>(floatMax),
        inclusive == JNI_TRUE,
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeStartUnknownSnapshot(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint dataType
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->startUnknownSnapshot(
        static_cast<mem_engine::DataType>(dataType),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeRefineFuzzy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint deltaOp,
    jlong intDelta,
    jdouble floatDelta,
    jdouble epsilon
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineFuzzy(
        static_cast<mem_engine::SearchOp>(deltaOp),
        static_cast<int64_t>(intDelta),
        static_cast<double>(floatDelta),
        static_cast<double>(epsilon),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeSearchEncoded(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jint dataType,
    jint encodedMode,
    jlong logicalInt,
    jdouble logicalFloat,
    jlong keyOrMask,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchEncoded(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::EncodedMode>(encodedMode),
        static_cast<int64_t>(logicalInt),
        static_cast<double>(logicalFloat),
        static_cast<uint64_t>(keyOrMask),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jlong JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeGetCandidateCount(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);
    return static_cast<jlong>(session->scanner->totalMatchedCount());
}

JNIEXPORT jlongArray JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeGetCandidateAddresses(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jint offset,
    jint limit
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || offset < 0 || limit <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<mem_engine::CandidateEntry> slice;
    session->scanner->fetchCandidates(static_cast<size_t>(offset), static_cast<size_t>(limit), slice);
    if (slice.empty()) return env->NewLongArray(0);

    size_t count = slice.size();
    std::vector<jlong> addrs(count);
    for (size_t i = 0; i < count; ++i) {
        addrs[i] = static_cast<jlong>(slice[i].address);
    }

    jlongArray arr = env->NewLongArray(static_cast<jsize>(count));
    if (arr) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(count), addrs.data());
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeWriteMemory(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);

    auto rc = session->io.writeMemory(static_cast<uintptr_t>(address), ptr, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
    return static_cast<jint>(rc);
}

JNIEXPORT void JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeLockAddress(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return;

    session->locker->lockAddress(static_cast<uintptr_t>(address), ptr, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeUnlockAddress(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jlong address
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAddress(static_cast<uintptr_t>(address));
}

// ============================================================================
// 2. GGNext Deep Integration JNI Bridge (com.littlecodesauce.ggnext.engine.NativeMemBridge)
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCreate(
    JNIEnv* env,
    jobject thiz,
    jint workerThreads,
    jstring spillDirJstr
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeCreate(env, thiz, workerThreads, spillDirJstr);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDestroy(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    Java_com_qa_memengine_MemDiagnosticEngine_nativeDestroy(env, thiz, handle);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAttach(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint pid
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeAttachProcess(env, thiz, handle, pid);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetRegionMask(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint regionMask
) {
    Java_com_qa_memengine_MemDiagnosticEngine_nativeSetRegionMask(env, thiz, handle, regionMask);
}

JNIEXPORT jlongArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetRegionTotals(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jint pid
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    if (pid > 0 && session->io.pid() == pid) {
        session->refreshAllRegions();
    } else if (pid > 0) {
        session->cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(static_cast<int>(pid), 0, false);
    }

    // 45 longs: [size_0..size_14, base_0..base_14, count_0..count_14] in GGNext RegionCatalog.ALL order
    jlong out[45];
    std::memset(out, 0, sizeof(out));

    for (const auto& r : session->cachedAllRegions) {
        for (int idx = 0; idx < 15; ++idx) {
            if (r.regionFlag == kGGNextRegionOrder[idx]) {
                out[idx] += static_cast<jlong>(r.length);
                if (out[15 + idx] == 0) {
                    out[15 + idx] = static_cast<jlong>(r.startAddress);
                }
                out[30 + idx] += 1;
                break;
            }
        }
    }

    jlongArray arr = env->NewLongArray(45);
    if (arr) {
        env->SetLongArrayRegion(arr, 0, 45, out);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetRegionCodeAt(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong address
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return env->NewStringUTF("O");
    std::lock_guard<std::mutex> lk(session->mtx);

    uintptr_t uaddr = static_cast<uintptr_t>(address) & 0x00FFFFFFFFFFFFFFULL;
    for (const auto& r : session->cachedAllRegions) {
        if (uaddr >= r.startAddress && uaddr < r.endAddress) {
            return env->NewStringUTF(mem_engine::regionFlagToTag(r.regionFlag));
        }
    }
    return env->NewStringUTF("O");
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeClassifyPointer(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jlong ptrValue
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    // Strip ARM64 Top-Byte-Ignore (TBI) tag bits
    uintptr_t uaddr = static_cast<uintptr_t>(ptrValue) & 0x00FFFFFFFFFFFFFFULL;
    if (uaddr < 0x10000ULL) return 0;

    for (const auto& r : session->cachedAllRegions) {
        if (uaddr >= r.startAddress && uaddr < r.endAddress) {
            if (!r.readable) return 0;
            if (r.writable && r.executable) return 1; // WX
            if (r.executable) return 2;               // X
            if (r.writable) return 16;                // W
            return 8;                                 // RO
        }
    }
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeReadBlock(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong address,
    jint size
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || size <= 0 || size > (16 * 1024 * 1024)) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<jbyte> buf(static_cast<size_t>(size), 0);
    size_t bytesRead = 0;
    session->io.readMemory(static_cast<uintptr_t>(address), buf.data(), static_cast<size_t>(size), &bytesRead);

    jbyteArray arr = env->NewByteArray(size);
    if (arr) {
        env->SetByteArrayRegion(arr, 0, size, buf.data());
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeWriteBlock(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    jint rc = Java_com_qa_memengine_MemDiagnosticEngine_nativeWriteMemory(env, thiz, handle, address, bytesJarr);
    return rc == static_cast<jint>(mem_engine::EngineErrc::OK) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCopyMemory(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jlong fromAddr,
    jlong toAddr,
    jlong length
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || length <= 0) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    const size_t total = static_cast<size_t>(std::min<int64_t>(length, 16LL * 1024LL * 1024LL));
    const size_t chunk = 64 * 1024;
    std::vector<uint8_t> buf(chunk);
    size_t copied = 0;

    while (copied < total) {
        size_t step = std::min(chunk, total - copied);
        size_t bytesRead = 0;
        auto rrc = session->io.readMemory(
            static_cast<uintptr_t>(fromAddr) + copied,
            buf.data(),
            step,
            &bytesRead
        );
        if (rrc != mem_engine::EngineErrc::OK || bytesRead == 0) break;
        auto wrc = session->io.writeMemory(
            static_cast<uintptr_t>(toAddr) + copied,
            buf.data(),
            bytesRead
        );
        if (wrc != mem_engine::EngineErrc::OK) break;
        copied += bytesRead;
    }
    return static_cast<jlong>(copied);
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDumpMemory(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jlong startAddr,
    jlong endAddr,
    jstring outFilePathJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || endAddr <= startAddr || !outFilePathJstr) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    const char* cpath = env->GetStringUTFChars(outFilePathJstr, nullptr);
    std::string outPath = cpath ? cpath : "";
    if (cpath) env->ReleaseStringUTFChars(outFilePathJstr, cpath);
    if (outPath.empty()) return 0;

    std::ofstream ofs(outPath, std::ios::out | std::ios::binary | std::ios::trunc);
    if (!ofs.is_open()) return 0;

    const size_t total = static_cast<size_t>(std::min<int64_t>(endAddr - startAddr, 64LL * 1024LL * 1024LL));
    const size_t chunk = 64 * 1024;
    std::vector<char> buf(chunk, 0);
    size_t written = 0;

    while (written < total) {
        size_t step = std::min(chunk, total - written);
        size_t bytesRead = 0;
        std::memset(buf.data(), 0, step);
        session->io.readMemory(
            static_cast<uintptr_t>(startAddr) + written,
            buf.data(),
            step,
            &bytesRead
        );
        ofs.write(buf.data(), static_cast<std::streamsize>(step));
        written += step;
    }
    ofs.close();
    return static_cast<jlong>(written);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchExact(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon,
    jboolean aligned
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeSearchExact(
        env, thiz, handle, dataType, intTarget, floatTarget, epsilon, aligned
    );
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineExact(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeRefineExact(
        env, thiz, handle, dataType, intTarget, floatTarget, epsilon
    );
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchRange(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint dataType,
    jlong intMin,
    jlong intMax,
    jdouble floatMin,
    jdouble floatMax,
    jboolean inclusive,
    jboolean aligned
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeSearchRange(
        env, thiz, handle, dataType, intMin, intMax, floatMin, floatMax, inclusive, aligned
    );
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchGroup(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jintArray typesJarr,
    jlongArray intValsJarr,
    jdoubleArray floatValsJarr,
    jint windowSize,
    jboolean ordered,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !typesJarr || !intValsJarr || !floatValsJarr) {
        return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    }
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(typesJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);

    jint* typesPtr = env->GetIntArrayElements(typesJarr, nullptr);
    jlong* intPtr = env->GetLongArrayElements(intValsJarr, nullptr);
    jdouble* fltPtr = env->GetDoubleArrayElements(floatValsJarr, nullptr);

    mem_engine::GroupSearchQuery query;
    query.fields.resize(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        query.fields[static_cast<size_t>(i)].type = static_cast<mem_engine::DataType>(typesPtr[i]);
        query.fields[static_cast<size_t>(i)].iVal = static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fVal = static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].epsilon = 1e-4;
        query.fields[static_cast<size_t>(i)].origIndex = static_cast<size_t>(i);
    }
    query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
    query.ordered = (ordered == JNI_TRUE);
    query.aligned = (aligned == JNI_TRUE);

    env->ReleaseIntArrayElements(typesJarr, typesPtr, 0);
    env->ReleaseLongArrayElements(intValsJarr, intPtr, 0);
    env->ReleaseDoubleArrayElements(floatValsJarr, fltPtr, 0);

    session->cancelToken.reset();
    auto rc = session->scanner->searchGroup(query, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchPattern(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray patternJarr,
    jstring maskJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !patternJarr) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(patternJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    jbyte* ptr = env->GetByteArrayElements(patternJarr, nullptr);
    std::vector<uint8_t> pat(reinterpret_cast<uint8_t*>(ptr), reinterpret_cast<uint8_t*>(ptr) + len);
    env->ReleaseByteArrayElements(patternJarr, ptr, 0);

    std::vector<uint8_t> maskBytes(static_cast<size_t>(len), 0xFF);
    if (maskJstr != nullptr) {
        const char* cmask = env->GetStringUTFChars(maskJstr, nullptr);
        if (cmask && cmask[0] != '\0') {
            size_t mlen = std::min(static_cast<size_t>(len), std::strlen(cmask));
            for (size_t i = 0; i < mlen; ++i) {
                maskBytes[i] = (cmask[i] == '?' || cmask[i] == '.') ? 0x00 : 0xFF;
            }
        }
        if (cmask) env->ReleaseStringUTFChars(maskJstr, cmask);
    }

    session->cancelToken.reset();
    auto rc = session->scanner->searchBytePattern(pat, maskBytes, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchEncoded(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint dataType,
    jint encodedMode,
    jlong logicalInt,
    jdouble logicalFloat,
    jlong keyOrMask,
    jboolean aligned
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeSearchEncoded(
        env, thiz, handle, dataType, encodedMode, logicalInt, logicalFloat, keyOrMask, aligned
    );
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeStartUnknownSnapshot(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint dataType
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeStartUnknownSnapshot(env, thiz, handle, dataType);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineFuzzy(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint deltaOp,
    jlong intDelta,
    jdouble floatDelta,
    jdouble epsilon
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeRefineFuzzy(
        env, thiz, handle, deltaOp, intDelta, floatDelta, epsilon
    );
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetCandidateCount(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    return Java_com_qa_memengine_MemDiagnosticEngine_nativeGetCandidateCount(env, thiz, handle);
}

JNIEXPORT jlongArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetCandidatesPacked(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jint offset,
    jint limit
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || offset < 0 || limit <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<mem_engine::CandidateEntry> slice;
    session->scanner->fetchCandidates(static_cast<size_t>(offset), static_cast<size_t>(limit), slice);
    if (slice.empty()) return env->NewLongArray(0);

    size_t count = slice.size();
    // Pack 3 longs per candidate: [address, rawValue, (type | (regionTag << 8))]
    std::vector<jlong> packed(count * 3);
    for (size_t i = 0; i < count; ++i) {
        const auto& c = slice[i];
        packed[i * 3 + 0] = static_cast<jlong>(c.address);
        packed[i * 3 + 1] = static_cast<jlong>(c.rawValue);
        packed[i * 3 + 2] = static_cast<jlong>(static_cast<uint64_t>(c.type) | (static_cast<uint64_t>(c.regionTag) << 8));
    }

    jlongArray arr = env->NewLongArray(static_cast<jsize>(count * 3));
    if (arr) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(count * 3), packed.data());
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLockAddress(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    Java_com_qa_memengine_MemDiagnosticEngine_nativeLockAddress(env, thiz, handle, address, bytesJarr);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeUnlockAddress(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong address
) {
    Java_com_qa_memengine_MemDiagnosticEngine_nativeUnlockAddress(env, thiz, handle, address);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeUnlockAll(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAll();
}

} // extern "C"
