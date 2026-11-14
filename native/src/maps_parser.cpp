#include "mem_engine/maps_parser.hpp"
#include <fstream>
#include <sstream>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <cctype>

namespace mem_engine {

namespace {

inline bool startsWith(std::string_view str, std::string_view prefix) noexcept {
    return str.size() >= prefix.size() && str.compare(0, prefix.size(), prefix) == 0;
}

inline bool endsWith(std::string_view str, std::string_view suffix) noexcept {
    return str.size() >= suffix.size() &&
           str.compare(str.size() - suffix.size(), suffix.size(), suffix) == 0;
}

inline bool containsStr(std::string_view str, std::string_view sub) noexcept {
    return str.find(sub) != std::string_view::npos;
}

inline bool isAppExecutablePath(std::string_view path) noexcept {
    if (startsWith(path, "/data/app/") ||
        startsWith(path, "/data/user/") ||
        startsWith(path, "/data/user_de/") ||
        startsWith(path, "/data/data/") ||
        startsWith(path, "/mnt/asec/") ||
        startsWith(path, "/mnt/expand/") ||
        startsWith(path, "/sdcard/") ||
        startsWith(path, "/storage/")) {
        return true;
    }
    if (startsWith(path, "/data/dalvik-cache/")) {
        return containsStr(path, "/data@app@") || containsStr(path, "/mnt@expand@");
    }
    return false;
}

inline bool isFontOrIcuBadPath(std::string_view path) noexcept {
    if (startsWith(path, "/system/fonts/") ||
        startsWith(path, "/product/fonts/") ||
        startsWith(path, "/system_ext/fonts/") ||
        startsWith(path, "/vendor/fonts/") ||
        startsWith(path, "/data/fonts/") ||
        startsWith(path, "/system/usr/icu/") ||
        startsWith(path, "/system/usr/hyphen-data/") ||
        startsWith(path, "/apex/com.android.i18n/")) {
        return true;
    }
    if (endsWith(path, ".ttf") || endsWith(path, ".otf") ||
        endsWith(path, ".ttc") || endsWith(path, ".woff") ||
        endsWith(path, ".hyb")) {
        return true;
    }
    if (endsWith(path, ".dat") && (containsStr(path, "icudt") || containsStr(path, "/icu/"))) {
        return true;
    }
    return false;
}

inline bool isDangerousDeviceOrKernelMapping(std::string_view path) noexcept {
    if (path == "[vvar]" || path == "[vectors]" || path == "[sigpage]" ||
        path == "[uprobes]" || path == "[kuserhelpers]") {
        return true;
    }
    if (startsWith(path, "/dev/binder") ||
        startsWith(path, "/dev/hwbinder") ||
        startsWith(path, "/dev/vndbinder") ||
        startsWith(path, "/dev/__properties__") ||
        startsWith(path, "/dev/event-log-tags") ||
        startsWith(path, "/dev/pmsg") ||
        startsWith(path, "/dev/kmsg") ||
        startsWith(path, "/dev/ion") ||
        startsWith(path, "/dev/dma_heap")) {
        return true;
    }
    return false;
}

inline bool isDalvikInternalRuntimeOrMetadata(std::string_view path) noexcept {
    return containsStr(path, "bitmap") ||
           containsStr(path, "mark-stack") ||
           containsStr(path, "allocation-stack") ||
           containsStr(path, "live-stack") ||
           containsStr(path, "card-table") ||
           containsStr(path, "card table") ||
           containsStr(path, "mod-union") ||
           containsStr(path, "LinearAlloc") ||
           containsStr(path, "indirect-ref") ||
           containsStr(path, "indirect ref") ||
           containsStr(path, "aux-structure") ||
           containsStr(path, "CompilerMetadata") ||
           containsStr(path, "jit-code-cache") ||
           containsStr(path, "jit-cache") ||
           containsStr(path, "jit-zygote") ||
           containsStr(path, "zygote space") ||
           containsStr(path, "image space") ||
           containsStr(path, "boot-image") ||
           containsStr(path, "ReserveAppImage") ||
           containsStr(path, "non moving space") ||
           containsStr(path, "classes.vdex") ||
           endsWith(path, ".art") ||
           endsWith(path, ".dex") ||
           endsWith(path, ".vdex");
}

inline bool isDalvikObjectHeapSpace(std::string_view path) noexcept {
    return containsStr(path, "main space") ||
           containsStr(path, "region space") ||
           containsStr(path, "alloc space") ||
           containsStr(path, "large object") ||
           containsStr(path, "los") ||
           containsStr(path, "bump-pointer") ||
           containsStr(path, "rosalloc") ||
           containsStr(path, "dlmalloc") ||
           containsStr(path, "free list");
}

inline bool isNativeLibPath(std::string_view path) noexcept {
    return containsStr(path, ".so") ||
           containsStr(path, "/lib/") ||
           containsStr(path, "/lib64/") ||
           startsWith(path, "/system/bin/") ||
           startsWith(path, "/apex/");
}

} // namespace

RegionFlag MapsParser::classifyVma(const VmaRegion& vma, std::string_view prevPath) noexcept {
    const std::string_view path = vma.path;

    if (containsStr(path, "kgsl") ||
        containsStr(path, "mali") ||
        containsStr(path, "pvrsrvkm") ||
        containsStr(path, "nvmap") ||
        containsStr(path, "tegra") ||
        containsStr(path, "/dev/dri/") ||
        containsStr(path, " gralloc") ||
        containsStr(path, "gralloc") ||
        containsStr(path, "GFX")) {
        return RegionFlag::V;
    }

    if (containsStr(path, "PPSSPP") || containsStr(path, "ppsspp")) {
        return RegionFlag::PS;
    }

    if (isFontOrIcuBadPath(path) || isDangerousDeviceOrKernelMapping(path)) {
        return RegionFlag::B;
    }
    if (!vma.readable) {
        return RegionFlag::O;
    }

    if (vma.executable) {
        if (path == "[vdso]" ||
            startsWith(path, "/memfd:jit-cache") ||
            startsWith(path, "/memfd:jit-zygote-cache") ||
            containsStr(path, "dalvik-jit-code-cache")) {
            return RegionFlag::XS;
        }
        if (isAppExecutablePath(path)) {
            return RegionFlag::XA;
        }
        if (path.empty() && vma.writable) {
            return RegionFlag::XA;
        }
        return RegionFlag::XS;
    }

    if (startsWith(path, "[stack") ||
        startsWith(path, "[anon:stack") ||
        containsStr(path, "stack_and_tls")) {
        return RegionFlag::S;
    }

    if (path == "[heap]" ||
        startsWith(path, "[anon:heap") ||
        containsStr(path, "libc_malloc")) {
        return RegionFlag::CH;
    }

    if (startsWith(path, "[anon:scudo:") ||
        startsWith(path, "[anon:GWP-ASan") ||
        startsWith(path, "[anon:jemalloc") ||
        containsStr(path, "scudo:") ||
        containsStr(path, "jemalloc")) {
        return RegionFlag::CA;
    }

    if (containsStr(path, "dalvik-") ||
        containsStr(path, "art-") ||
        startsWith(path, "/memfd:jit-cache") ||
        startsWith(path, "/memfd:jit-zygote-cache") ||
        startsWith(path, "/memfd:dalvik-") ||
        endsWith(path, ".art")) {
        if (isDalvikInternalRuntimeOrMetadata(path) || !vma.writable) {
            return RegionFlag::J;
        }
        if (isDalvikObjectHeapSpace(path)) {
            return RegionFlag::JH;
        }
        return RegionFlag::J;
    }

    if (startsWith(path, "[anon:.bss") || containsStr(path, ".bss")) {
        return RegionFlag::CB;
    }
    if (path.empty() && vma.writable && !vma.executable && isNativeLibPath(prevPath)) {
        return RegionFlag::CB;
    }

    if (startsWith(path, "/dev/ashmem") ||
        startsWith(path, "[anon:ashmem") ||
        startsWith(path, "/memfd:ashmem")) {
        return RegionFlag::AS;
    }

    if (startsWith(path, "/dev/")) {
        if (startsWith(path, "/dev/zero")) {
            return vma.writable ? RegionFlag::CA : RegionFlag::O;
        }
        return RegionFlag::B;
    }

    if (path.empty() || path == "[anon]" || startsWith(path, "[anon:")) {
        if (vma.writable && !vma.executable) {
            return RegionFlag::A;
        }
        return RegionFlag::O;
    }

    if (isNativeLibPath(path)) {
        if (vma.writable && !vma.executable) {
            return RegionFlag::CD;
        }
        return RegionFlag::O;
    }

    return RegionFlag::O;
}

bool MapsParser::parseLine(std::string_view line, std::string_view prevPath, VmaRegion& outRegion) noexcept {
    if (line.empty()) return false;

    const char* begin = line.data();
    const char* end = begin + line.size();
    char* nextPtr = nullptr;

    uint64_t startAddr = std::strtoull(begin, &nextPtr, 16);
    if (nextPtr == begin || nextPtr >= end || *nextPtr != '-') return false;

    const char* dashAfter = nextPtr + 1;
    uint64_t endAddr = std::strtoull(dashAfter, &nextPtr, 16);
    if (nextPtr == dashAfter || nextPtr >= end || endAddr <= startAddr) return false;

    const char* p = nextPtr;
    while (p < end && *p == ' ') ++p;
    if (end - p < 4) return false;

    outRegion.start = startAddr;
    outRegion.end = endAddr;
    outRegion.readable   = (p[0] == 'r');
    outRegion.writable   = (p[1] == 'w');
    outRegion.executable = (p[2] == 'x');
    outRegion.isPrivate  = (p[3] == 'p');
    p += 4;

    while (p < end && *p == ' ') ++p;
    outRegion.offset = std::strtoull(p, &nextPtr, 16);
    if (nextPtr == p) return false;
    p = nextPtr;

    while (p < end && *p == ' ') ++p;
    const char* devStart = p;
    while (p < end && *p != ' ') ++p;
    outRegion.device.assign(devStart, static_cast<size_t>(p - devStart));

    while (p < end && *p == ' ') ++p;
    outRegion.inode = std::strtoull(p, &nextPtr, 10);
    if (nextPtr == p) return false;
    p = nextPtr;

    while (p < end && *p == ' ') ++p;
    if (p < end) {
        size_t len = static_cast<size_t>(end - p);
        while (len > 0 && (p[len - 1] == '\n' || p[len - 1] == '\r' || p[len - 1] == ' ')) {
            --len;
        }
        outRegion.path.assign(p, len);
    } else {
        outRegion.path.clear();
    }

    outRegion.regionFlag = classifyVma(outRegion, prevPath);
    return true;
}

std::vector<VmaRegion> MapsParser::parseContent(std::string_view content, uint32_t regionMask) {
    std::vector<VmaRegion> results;
    results.reserve(512);

    std::string prevPath;
    size_t pos = 0;
    const size_t total = content.size();

    while (pos < total) {
        size_t nl = content.find('\n', pos);
        if (nl == std::string_view::npos) nl = total;

        std::string_view line = content.substr(pos, nl - pos);
        VmaRegion vma;
        if (parseLine(line, prevPath, vma)) {
            if (!vma.path.empty()) {
                prevPath = vma.path;
            } else {
                prevPath.clear();
            }
            if ((static_cast<uint32_t>(vma.regionFlag) & regionMask) != 0) {
                results.push_back(std::move(vma));
            }
        }
        pos = nl + 1;
    }

    return results;
}

Result<std::vector<VmaRegion>> MapsParser::parse(pid_t pid, uint32_t regionMask) {
    if (pid <= 0) {
        return Result<std::vector<VmaRegion>>::err(StatusCode::ERR_INVALID_PID, "Invalid PID for maps parser");
    }

    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream file(mapsPath, std::ios::in | std::ios::binary);
    if (!file.is_open()) {
        return Result<std::vector<VmaRegion>>::err(StatusCode::ERR_PERMISSION_DENIED,
                                                   "Cannot open " + mapsPath);
    }

    std::ostringstream ss;
    ss << file.rdbuf();
    std::string content = ss.str();
    if (content.empty()) {
        return Result<std::vector<VmaRegion>>::err(StatusCode::ERR_EMPTY_MAPS,
                                                   "Empty maps content for PID " + std::to_string(pid));
    }

    return Result<std::vector<VmaRegion>>::ok(parseContent(content, regionMask));
}

} // namespace mem_engine
