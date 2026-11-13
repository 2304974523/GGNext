#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/search_engine.hpp"
#include <memory>
#include <mutex>
#include <cstring>

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
using jlongArray    = _jobject*;
using jbyteArray    = _jobject*;
using jobjectArray  = _jobject*;
struct JNIEnv {
    const char* GetStringUTFChars(jstring, jboolean*) { return ""; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jsize GetArrayLength(jobject) { return 0; }
    jbyte* GetByteArrayElements(jbyteArray, jboolean*) { return nullptr; }
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}
    jlong* GetLongArrayElements(jlongArray, jboolean*) { return nullptr; }
    void ReleaseLongArrayElements(jlongArray, jlong*, jint) {}
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
    std::mutex                        mtx;
    mem_engine::ProcIO                io;
    std::unique_ptr<mem_engine::SearchEngine> scanner;
    std::unique_ptr<mem_engine::ValueLocker>  locker;
    mem_engine::CancellationToken     cancelToken;

    explicit NativeEngineSession(size_t threads, const std::string& spillDir)
        : scanner(std::make_unique<mem_engine::SearchEngine>(io, threads, spillDir)),
          locker(std::make_unique<mem_engine::ValueLocker>(io, 5)) {}
};

} // anonymous namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_qa_memengine_MemDiagnosticEngine_nativeCreate(
    JNIEnv* env,
    jobject /*thiz*/,
    jint workerThreads,
    jstring spillDirJstr
) {
    std::string spillDir = "/tmp";
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
    return static_cast<jlong>(session->scanner->candidateCount());
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

    const auto& store = session->scanner->candidates();
    size_t start = static_cast<size_t>(offset);
    if (start >= store.size()) return env->NewLongArray(0);
    size_t count = std::min(static_cast<size_t>(limit), store.size() - start);

    std::vector<jlong> addrs(count);
    for (size_t i = 0; i < count; ++i) {
        addrs[i] = static_cast<jlong>(store[start + i].address);
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

} // extern "C"
