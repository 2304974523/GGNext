#include "mem_engine/maps_parser.hpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <fstream>
#include <sstream>

namespace mem_engine {

namespace {

inline bool startsWith(const std::string& s, const char* prefix) noexcept {
    size_t plen = std::strlen(prefix);
    return s.size() >= plen && std::memcmp(s.data(), prefix, plen) == 0;
}

inline bool containsStr(const std::string& s, const char* sub) noexcept {
    return s.find(sub) != std::string::npos;
}

inline bool endsWith(const std::string& s, const char* suffix) noexcept {
    size_t slen = std::strlen(suffix);
    return s.size() >= slen && std::memcmp(s.data() + s.size() - slen, suffix, slen) == 0;
}

inline std::string stripDeleted(const std::string& raw) {
    const char* delSuffix = " (deleted)";
    size_t dlen = std::strlen(delSuffix);
    if (raw.size() >= dlen && raw.compare(raw.size() - dlen, dlen, delSuffix) == 0) {
        return raw.substr(0, raw.size() - dlen);
    }
    return raw;
}

} // anonymous namespace

uint32_t MapsParser::classifyVma(const VmaRegion& vma, const std::string& prevFileBackedPath) {
    if (vma.length == 0) {
        return RegionFlag::B;
    }

    const std::string path = stripDeleted(vma.pathname);

    // 1. PPSSPP Emulator Regions (Ps)
    if (vma.readable && (containsStr(path, "ppsspp") || containsStr(path, "PPSSPP") ||
        containsStr(path, "psp_ram") || containsStr(path, "psp_vram") ||
        startsWith(path, "/dev/shm/ppsspp"))) {
        return RegionFlag::PS;
    }

    // 2. Bad (B): Fonts, ICU/Hyphenation Data (~137 MB), Kernel Trap Pages, Binder & Special Device Nodes
    // Checked BEFORE !vma.readable so ---p guard/reservation mappings (3.5+ GB) fall into O (Other) just like original GG,
    // while genuine Bad mappings are tagged B (~137 MB). Both B and !vma.readable are skipped during scanning via isScanSafe().
    if (endsWith(path, ".ttf") || endsWith(path, ".otf") || endsWith(path, ".ttc") || endsWith(path, ".woff") ||
        containsStr(path, "/system/fonts/") || containsStr(path, "/product/fonts/") || containsStr(path, "/vendor/fonts/") ||
        startsWith(path, "/system/usr/icu/") || startsWith(path, "/apex/com.android.i18n/etc/icu/") ||
        (containsStr(path, "/icu/") && endsWith(path, ".dat")) ||
        startsWith(path, "/system/usr/hyphen-data/") || endsWith(path, ".hyb") ||
        path == "[vvar]" || path == "[vectors]" || path == "[sigpage]" || path == "[uprobes]" || path == "[kuserhelpers]" ||
        startsWith(path, "/dev/binder") || startsWith(path, "/dev/hwbinder") ||
        startsWith(path, "/dev/vndbinder") || startsWith(path, "/dev/__properties__") ||
        startsWith(path, "/dev/event-log-tags") || startsWith(path, "/dev/pmsg") ||
        startsWith(path, "/dev/kmsg") || startsWith(path, "/dev/null") ||
        startsWith(path, "/dev/urandom") || startsWith(path, "/dev/random") ||
        startsWith(path, "/dev/cpuctl") || startsWith(path, "/dev/cpuset") ||
        startsWith(path, "/dev/stune") || startsWith(path, "/dev/blkio") ||
        startsWith(path, "[anon:GWP-ASan Guard") || startsWith(path, "[anon:guard")) {
        return RegionFlag::B;
    }

    // 3. Unreadable / PROT_NONE virtual address reservations (---p) -> O (Other)
    // Original GG counts ---p reservations in O (~3.9 GB), while VmaRegion::isScanSafe() skips !readable pages.
    if (!vma.readable) {
        return RegionFlag::O;
    }

    // 4. Video / GPU / DMA-BUF / ION / KGSL / Gralloc (V)
    if (startsWith(path, "/dev/kgsl") || containsStr(path, "kgsl-3d0") ||
        startsWith(path, "/dev/mali") || startsWith(path, "/dev/ion") ||
        startsWith(path, "/dev/dma_heap") || startsWith(path, "/dev/dri/") ||
        startsWith(path, "/dev/nvidia") || startsWith(path, "/dev/pvrsrvkm") ||
        startsWith(path, "/dev/galcore") || containsStr(path, "gralloc") ||
        startsWith(path, "/dmabuf:") || startsWith(path, "[anon:dmabuf")) {
        return RegionFlag::V;
    }

    // 5. Executable Code Regions: App Code (Xa) vs System Code (Xs)
    // Original GG strictly separates app binaries (/data/app, /data/user, app dalvik-cache) into Xa (~16 MB)
    // and all system/APEX/vendor libraries, boot/system dalvik-cache, [vdso], and ART JIT code-cache into Xs (~151 MB).
    if (vma.executable) {
        if (path == "[vdso]" || path == "[vsyscall]" ||
            startsWith(path, "/memfd:jit-") ||
            containsStr(path, "jit-code-cache") ||
            containsStr(path, "jit-zygote-cache")) {
            return RegionFlag::XS;
        }
        if (startsWith(path, "/data/dalvik-cache/")) {
            if (containsStr(path, "data@app@") || containsStr(path, "mnt@expand@") || containsStr(path, "data@data@")) {
                return RegionFlag::XA;
            }
            return RegionFlag::XS;
        }
        bool isAppExec = startsWith(path, "/data/app/") ||
                         startsWith(path, "/data/data/") ||
                         startsWith(path, "/data/user/") ||
                         startsWith(path, "/data/user_de/") ||
                         startsWith(path, "/mnt/asec/") ||
                         startsWith(path, "/mnt/expand/") ||
                         startsWith(path, "/sdcard/") ||
                         startsWith(path, "/storage/") ||
                         (path.empty() && vma.writable && vma.isPrivate);
        return isAppExec ? RegionFlag::XA : RegionFlag::XS;
    }

    // 6. Stack (S)
    if (path == "[stack]" || startsWith(path, "[stack:") ||
        startsWith(path, "[anon:stack_and_tls") || startsWith(path, "[anon:thread signal stack")) {
        return RegionFlag::S;
    }

    // 7. Native C/C++ Heap (Ch)
    if (path == "[heap]" || startsWith(path, "[heap:") || startsWith(path, "[anon:heap")) {
        return RegionFlag::CH;
    }

    // 8. Java Heap (Jh) vs Java Runtime Structures (J)
    // Non-executable /memfd:jit-cache and /memfd:jit-zygote-cache belong to J (NOT As!).
    if (startsWith(path, "/memfd:jit-")) {
        return RegionFlag::J;
    }
    if (containsStr(path, "dalvik-") || startsWith(path, "[anon:dalvik-") ||
        startsWith(path, "/dev/ashmem/dalvik-") || startsWith(path, "/memfd:dalvik-") ||
        startsWith(path, "[anon:art_") ||
        containsStr(path, "main space") || containsStr(path, "alloc space") ||
        containsStr(path, "region space") || containsStr(path, "large object space") ||
        containsStr(path, "non moving space") || containsStr(path, "zygote space") ||
        containsStr(path, "bump-pointer space") || containsStr(path, "rosalloc space") ||
        containsStr(path, "dlmalloc space") || containsStr(path, "free list large") ||
        containsStr(path, "image space")) {
        bool isDalvikInternalMeta =
            containsStr(path, "bitmap") ||
            containsStr(path, "mark-stack") ||
            containsStr(path, "allocation-stack") ||
            containsStr(path, "live-stack") ||
            containsStr(path, "card-table") ||
            containsStr(path, "card table") ||
            containsStr(path, "mod-union") ||
            containsStr(path, "LinearAlloc") ||
            containsStr(path, "indirect-ref") ||
            containsStr(path, "reference table") ||
            containsStr(path, "aux-structure") ||
            containsStr(path, "CompilerMetadata") ||
            containsStr(path, "jit") ||
            containsStr(path, "zygote") ||
            containsStr(path, "non moving") ||
            containsStr(path, "image space") ||
            containsStr(path, "intern table") ||
            containsStr(path, "class table");

        bool isJavaObjectAllocHeap =
            containsStr(path, "main space") ||
            containsStr(path, "alloc space") ||
            containsStr(path, "region space") ||
            containsStr(path, "large object") ||
            containsStr(path, "bump-pointer") ||
            containsStr(path, "rosalloc") ||
            containsStr(path, "dlmalloc") ||
            containsStr(path, "free list");

        if (vma.writable && isJavaObjectAllocHeap && !isDalvikInternalMeta) {
            return RegionFlag::JH;
        }
        return RegionFlag::J;
    }
    if (endsWith(path, ".art") || endsWith(path, ".vdex") || endsWith(path, ".dex") || containsStr(path, "classes.dex")) {
        return RegionFlag::J;
    }

    // 9. C++ Allocator (Ca): Bionic libc_malloc, Scudo, Jemalloc, GWP-ASan
    if (startsWith(path, "[anon:libc_malloc") ||
        startsWith(path, "[anon:scudo:") ||
        startsWith(path, "[anon:jemalloc") ||
        startsWith(path, "[anon:GWP-ASan") ||
        startsWith(path, "[anon:malloc") ||
        startsWith(path, "[anon:bionic_alloc") ||
        startsWith(path, "[anon:mtk_malloc")) {
        return RegionFlag::CA;
    }

    // 10. C++ .bss Segment (Cb)
    if (path == "[anon:.bss]" || startsWith(path, "[anon:.bss") || startsWith(path, "[anon:bss")) {
        return RegionFlag::CB;
    }
    if (path.empty() && vma.writable && !vma.executable && vma.isPrivate && !prevFileBackedPath.empty()) {
        if (endsWith(prevFileBackedPath, ".so") || containsStr(prevFileBackedPath, ".so!")) {
            return RegionFlag::CB;
        }
    }

    // 11. C++ .data Segment (Cd): Writable private segment of an ELF shared library (.so / .oat / .odex)
    if (vma.writable && vma.isPrivate &&
        (endsWith(path, ".so") || containsStr(path, ".so!") || endsWith(path, ".oat") || endsWith(path, ".odex"))) {
        return RegionFlag::CD;
    }

    // 12. Ashmem Shared Memory (As)
    // Strictly genuine Ashmem (/dev/ashmem excluding dalvik/jit/gralloc, [anon:ashmem, /memfd:ashmem).
    if ((startsWith(path, "/dev/ashmem") && !containsStr(path, "dalvik") &&
         !containsStr(path, "jit") && !containsStr(path, "gralloc") && !containsStr(path, "GFX")) ||
        startsWith(path, "[anon:ashmem") || startsWith(path, "/memfd:ashmem")) {
        return RegionFlag::AS;
    }

    // 13. Pure Anonymous Memory (A)
    if (path.empty() || startsWith(path, "[anon:")) {
        if (vma.writable && vma.isPrivate) {
            return RegionFlag::A;
        }
        return RegionFlag::O;
    }

    return RegionFlag::O;
}

std::vector<VmaRegion> MapsParser::parseMapsContent(const std::string& content, uint32_t filterMask, bool skipUnreadable) {
    std::vector<VmaRegion> regions;
    regions.reserve(512);

    std::string prevFileBackedPath;
    size_t pos = 0;
    const size_t totalLen = content.size();

    while (pos < totalLen) {
        size_t lineEnd = content.find('\n', pos);
        if (lineEnd == std::string::npos) lineEnd = totalLen;

        const char* linePtr = content.data() + pos;
        size_t lineLen = lineEnd - pos;
        pos = lineEnd + 1;

        if (lineLen == 0) continue;

        // Parse: start-end perms offset dev inode [pathname]
        char* endPtr = nullptr;
        unsigned long long start = std::strtoull(linePtr, &endPtr, 16);
        if (!endPtr || *endPtr != '-') continue;

        unsigned long long end = std::strtoull(endPtr + 1, &endPtr, 16);
        if (!endPtr || end <= start) continue;

        while (*endPtr == ' ' || *endPtr == '\t') ++endPtr;
        if (static_cast<size_t>(endPtr - linePtr) + 4 > lineLen) continue;

        char perms[5] = { endPtr[0], endPtr[1], endPtr[2], endPtr[3], '\0' };
        endPtr += 4;

        unsigned long long offset = std::strtoull(endPtr, &endPtr, 16);
        while (*endPtr == ' ' || *endPtr == '\t') ++endPtr;

        const char* devStart = endPtr;
        while (static_cast<size_t>(endPtr - linePtr) < lineLen && *endPtr != ' ' && *endPtr != '\t') ++endPtr;
        std::string devStr(devStart, static_cast<size_t>(endPtr - devStart));

        unsigned long long inode = std::strtoull(endPtr, &endPtr, 10);
        while (static_cast<size_t>(endPtr - linePtr) < lineLen && (*endPtr == ' ' || *endPtr == '\t')) ++endPtr;

        std::string pathname;
        if (static_cast<size_t>(endPtr - linePtr) < lineLen) {
            size_t pathLen = lineLen - static_cast<size_t>(endPtr - linePtr);
            while (pathLen > 0 && (endPtr[pathLen - 1] == '\r' || endPtr[pathLen - 1] == '\n' || endPtr[pathLen - 1] == ' ')) {
                --pathLen;
            }
            pathname.assign(endPtr, pathLen);
        }

        VmaRegion vma;
        vma.startAddress = static_cast<uintptr_t>(start);
        vma.endAddress   = static_cast<uintptr_t>(end);
        vma.length       = static_cast<size_t>(end - start);
        vma.fileOffset   = offset;
        vma.inode        = inode;
        vma.readable     = (perms[0] == 'r');
        vma.writable     = (perms[1] == 'w');
        vma.executable   = (perms[2] == 'x');
        vma.isPrivate    = (perms[3] == 'p');
        vma.isShared     = (perms[3] == 's');
        vma.dev          = std::move(devStr);
        vma.pathname     = std::move(pathname);

        vma.regionFlag   = classifyVma(vma, prevFileBackedPath);

        // Track last file-backed .so mapping to accurately tag the single immediately following unnamed rw-p as Cb (.bss)
        if (!vma.pathname.empty() && vma.pathname[0] == '/') {
            prevFileBackedPath = stripDeleted(vma.pathname);
        } else {
            prevFileBackedPath.clear();
        }

        if (skipUnreadable && (!vma.readable || vma.regionFlag == RegionFlag::B)) {
            continue;
        }
        if (filterMask != 0 && (vma.regionFlag & filterMask) == 0) {
            continue;
        }

        regions.push_back(std::move(vma));
    }

    return regions;
}

std::vector<VmaRegion> MapsParser::parseProcessMaps(int pid, uint32_t filterMask, bool skipUnreadable) {
    if (pid <= 0) return {};
    std::string path = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream ifs(path, std::ios::in | std::ios::binary);
    if (!ifs.is_open()) return {};

    std::ostringstream oss;
    oss << ifs.rdbuf();
    return parseMapsContent(oss.str(), filterMask, skipUnreadable);
}

bool MapsParser::querySmapsForAddress(int pid, uintptr_t targetStart, SmapsDetail& outDetail) {
    if (pid <= 0) return false;
    std::string path = "/proc/" + std::to_string(pid) + "/smaps";
    FILE* fp = std::fopen(path.c_str(), "r");
    if (!fp) return false;

    outDetail = SmapsDetail{};
    char line[1024];
    bool inTargetVma = false;

    while (std::fgets(line, sizeof(line), fp)) {
        // Check header line: hex-hex perms ...
        if (std::isxdigit(static_cast<unsigned char>(line[0]))) {
            char* endPtr = nullptr;
            unsigned long long start = std::strtoull(line, &endPtr, 16);
            if (endPtr && *endPtr == '-') {
                if (inTargetVma) break; // Finished reading target VMA block
                if (static_cast<uintptr_t>(start) == targetStart) {
                    inTargetVma = true;
                }
                continue;
            }
        }

        if (!inTargetVma) continue;

        size_t val = 0;
        if (std::sscanf(line, "Rss: %zu kB", &val) == 1) outDetail.rssKB = val;
        else if (std::sscanf(line, "Pss: %zu kB", &val) == 1) outDetail.pssKB = val;
        else if (std::sscanf(line, "Shared_Dirty: %zu kB", &val) == 1) outDetail.sharedDirtyKB = val;
        else if (std::sscanf(line, "Private_Dirty: %zu kB", &val) == 1) outDetail.privateDirtyKB = val;
        else if (std::sscanf(line, "Anonymous: %zu kB", &val) == 1) outDetail.anonymousKB = val;
        else if (std::sscanf(line, "Swap: %zu kB", &val) == 1) outDetail.swapKB = val;
        else if (std::sscanf(line, "KernelPageSize: %zu kB", &val) == 1) outDetail.kernelPageKB = val;
    }

    std::fclose(fp);
    return inTargetVma;
}

} // namespace mem_engine
