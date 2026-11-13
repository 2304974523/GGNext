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
    // 1. Unreadable or guard pages (---p) -> B (Bad)
    if (!vma.readable || vma.length == 0) {
        return RegionFlag::B;
    }

    const std::string path = stripDeleted(vma.pathname);

    // 2. PPSSPP Emulator Regions (Ps)
    if (containsStr(path, "ppsspp") || containsStr(path, "PPSSPP") ||
        containsStr(path, "psp_ram") || containsStr(path, "psp_vram") ||
        startsWith(path, "/dev/shm/ppsspp")) {
        return RegionFlag::PS;
    }

    // 3. Bad / Guard / Font / Non-Memory Device Nodes (B)
    if (endsWith(path, ".ttf") || endsWith(path, ".otf") || endsWith(path, ".ttc") ||
        containsStr(path, "/system/fonts/") ||
        path == "[vvar]" || path == "[vectors]" || path == "[sigpage]" || path == "[uprobes]" ||
        startsWith(path, "/dev/binder") || startsWith(path, "/dev/hwbinder") ||
        startsWith(path, "/dev/vndbinder") || startsWith(path, "/dev/null") ||
        startsWith(path, "/dev/zero") || startsWith(path, "/dev/urandom") ||
        startsWith(path, "/dev/random") || startsWith(path, "/dev/cpuctl") ||
        startsWith(path, "/dev/cpuset") || startsWith(path, "/dev/stune") ||
        startsWith(path, "/dev/blkio") || startsWith(path, "/dev/__properties__") ||
        startsWith(path, "/dev/event-log-tags") ||
        startsWith(path, "[anon:GWP-ASan Guard") || containsStr(path, "guard")) {
        return RegionFlag::B;
    }

    // 4. Video / GPU / DMA-BUF / ION / KGSL (V)
    if (startsWith(path, "/dev/kgsl") || containsStr(path, "kgsl-3d0") ||
        startsWith(path, "/dev/mali") || startsWith(path, "/dev/ion") ||
        startsWith(path, "/dev/dma_heap") || startsWith(path, "/dev/dri/") ||
        startsWith(path, "/dev/nvidia") || startsWith(path, "/dev/pvrsrvkm") ||
        startsWith(path, "/dev/galcore") || containsStr(path, "gralloc") ||
        startsWith(path, "/dmabuf:")) {
        return RegionFlag::V;
    }

    // 5. Stack (S)
    if (path == "[stack]" || startsWith(path, "[stack:") || startsWith(path, "[anon:stack_and_tls")) {
        return RegionFlag::S;
    }

    // 6. Native C/C++ Heap (Ch)
    if (path == "[heap]" || startsWith(path, "[heap:")) {
        return RegionFlag::CH;
    }

    // 7. Java / ART Virtual Machine Heap (Jh)
    if (containsStr(path, "dalvik-") || startsWith(path, "[anon:dalvik-") ||
        startsWith(path, "/dev/ashmem/dalvik-") ||
        startsWith(path, "[anon:art_") ||
        path == "[anon:main space]" || path == "[anon:alloc space]" ||
        path == "[anon:region space]" || path == "[anon:large object space]" ||
        path == "[anon:non moving space]" || startsWith(path, "[anon:zygote")) {
        if (vma.executable) {
            return RegionFlag::XA; // ART JIT Code Cache
        }
        if (vma.writable) {
            return RegionFlag::JH;
        }
        return RegionFlag::O;
    }

    // 8. C++ Allocator (Ca): Bionic libc_malloc, Scudo, Jemalloc, GWP-ASan
    if (startsWith(path, "[anon:libc_malloc") ||
        startsWith(path, "[anon:scudo:") ||
        startsWith(path, "[anon:jemalloc") ||
        startsWith(path, "[anon:GWP-ASan") ||
        startsWith(path, "[anon:malloc") ||
        startsWith(path, "[anon:bionic_alloc")) {
        return RegionFlag::CA;
    }

    // 9. C++ .bss Segment (Cb)
    if (path == "[anon:.bss]" || startsWith(path, "[anon:bss")) {
        return RegionFlag::CB;
    }
    // Note: On older kernels or specific linkers, a .so's .bss segment is an unnamed anonymous
    // rw-p VMA immediately following the .so's rw-p .data VMA.
    if (path.empty() && vma.writable && !vma.executable && vma.isPrivate && !prevFileBackedPath.empty()) {
        if (endsWith(prevFileBackedPath, ".so") || containsStr(prevFileBackedPath, ".so!")) {
            return RegionFlag::CB;
        }
    }

    // 10. Ashmem & Memfd Shared Memory (As)
    if (startsWith(path, "/dev/ashmem") || startsWith(path, "[anon:ashmem") ||
        startsWith(path, "/memfd:")) {
        if (vma.executable) {
            return RegionFlag::XA; // JIT cache in memfd
        }
        return RegionFlag::AS;
    }

    // 11. Pure Anonymous Memory (A) or Anonymous Executable JIT (Xa)
    if (path.empty() || startsWith(path, "[anon:")) {
        if (vma.executable) {
            return RegionFlag::XA;
        }
        if (vma.writable) {
            return RegionFlag::A;
        }
        return RegionFlag::O;
    }

    // 12. System Special Executable ([vdso], [vsyscall]) -> Xs
    if (path == "[vdso]" || path == "[vsyscall]") {
        return RegionFlag::XS;
    }

    // 13. File-Backed Mappings (System vs App Code & Data)
    bool isSystemPath = startsWith(path, "/system/") ||
                        startsWith(path, "/apex/") ||
                        startsWith(path, "/vendor/") ||
                        startsWith(path, "/product/") ||
                        startsWith(path, "/system_ext/") ||
                        startsWith(path, "/odm/") ||
                        startsWith(path, "/usr/lib") ||
                        startsWith(path, "/lib/");

    bool isAppPath = startsWith(path, "/data/app/") ||
                     startsWith(path, "/data/data/") ||
                     startsWith(path, "/data/user/") ||
                     startsWith(path, "/data/user_de/") ||
                     startsWith(path, "/mnt/expand/");

    bool isSharedLibOrExec = endsWith(path, ".so") || containsStr(path, ".so!") ||
                             endsWith(path, ".oat") || endsWith(path, ".odex") ||
                             vma.executable;

    if (vma.executable) {
        if (isSystemPath && !isAppPath) {
            return RegionFlag::XS;
        }
        return RegionFlag::XA;
    }

    // Writable private file-backed segment of an ELF binary or .so/.art -> Cd (.data)
    if (vma.writable && vma.isPrivate && (isSharedLibOrExec || isAppPath || isSystemPath || path[0] == '/')) {
        if (endsWith(path, ".so") || containsStr(path, ".so") || endsWith(path, ".art") || vma.inode > 0) {
            return RegionFlag::CD;
        }
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
