#pragma once
// =============================================================================
// Paged-KV increment 1 — session -> sequence registry (arch doc Part 4).
//
// STATUS: UNBUILT. Written against the llama.cpp memory API used in llama_runner.cpp
// (llama_get_memory / llama_memory_seq_rm / llama_memory_seq_cp), but NOT yet compiled
// on device. Confirm the exact symbol signatures against the pinned llama.cpp submodule
// at build time (the `llama_memory_*` names are current; older trees use `llama_kv_*`).
//
// This is step 1 of the build order in docs/PHASE1-KV-POOLING.md: the pool + isolation,
// WITHOUT continuous batching yet. It maps (owner uid, sessionId) -> a llama sequence id
// drawn from a fixed pool, with LRU eviction and per-uid isolation. It deliberately does
// NOT drive decode — wiring it into a batched step() loop is increment 2. Kept in its own
// header so it can be included + unit-tested without disturbing the confirmed-working
// single-context generate() path in llama_runner.cpp.
// =============================================================================
#include "llama.h"

#include <cstdint>
#include <list>
#include <string>
#include <unordered_map>

namespace edgelm {

// A shared KV pool over one multi-sequence llama_context. Each logical conversation
// (keyed by the caller's uid + sessionId) is assigned one llama_seq_id from the pool.
// Isolation: a sequence is tagged with its owner uid; lookups require a matching uid, so
// one app can never address another app's KV. Eviction is LRU via llama_memory_seq_rm.
//
// The owning llama_context MUST be created with n_seq_max >= pool_size (see load_model's
// llama_context_params; today it defaults to 1 — increment 2 raises it).
class SessionRegistry {
public:
    // Sentinel for "no sequence".
    static constexpr llama_seq_id kNone = -1;

    // [ctx] must outlive this registry. [pool_size] = max concurrent resident sequences
    // (== n_seq_max the context was built with). Sequence ids are [0, pool_size).
    SessionRegistry(llama_context* ctx, int pool_size)
        : ctx_(ctx), mem_(llama_get_memory(ctx)), pool_size_(pool_size) {
        for (llama_seq_id s = 0; s < pool_size_; ++s) free_.push_back(s);
    }

    // Get (or assign) the sequence id for (uid, sessionId). Never returns another uid's
    // sequence. If the pool is full, LRU-evicts the least-recently-used sequence first
    // (dropping its KV). Returns kNone only if pool_size <= 0.
    llama_seq_id acquire(uint32_t uid, const std::string& session_id) {
        const std::string key = make_key(uid, session_id);
        auto it = map_.find(key);
        if (it != map_.end()) { touch(it->second); return it->second.seq; }

        llama_seq_id seq;
        if (!free_.empty()) {
            seq = free_.front();
            free_.pop_front();
        } else {
            seq = evict_lru();               // frees a victim's KV, returns its slot
            if (seq == kNone) return kNone;
        }
        Slot slot{seq, uid, ++tick_};
        // Fresh sequence: make sure no stale KV lingers on this seq id.
        llama_memory_seq_rm(mem_, seq, /*p0=*/0, /*p1=*/-1);
        lru_.push_front(key);
        slot.lru_it = lru_.begin();
        map_.emplace(key, slot);
        return seq;
    }

    // Release (uid, sessionId): drop its KV and return the sequence to the pool. No-op if
    // the caller doesn't own it (isolation) or it isn't resident.
    void release(uint32_t uid, const std::string& session_id) {
        const std::string key = make_key(uid, session_id);
        auto it = map_.find(key);
        if (it == map_.end() || it->second.uid != uid) return;
        llama_memory_seq_rm(mem_, it->second.seq, 0, -1);
        free_.push_back(it->second.seq);
        lru_.erase(it->second.lru_it);
        map_.erase(it);
    }

    // Copy-on-write share of a PUBLIC prefix [0, prefix_len) from an already-prefilled
    // source sequence into a destination sequence (Part 4 / Part 11). Caller is
    // responsible for only ever passing a non-sensitive, identical prefix (e.g. a shared
    // system prompt) — never user content, and never across mutually-distrusting uids
    // unless the prefix is genuinely public. Returns true on success.
    bool cow_prefix(llama_seq_id src, llama_seq_id dst, llama_pos prefix_len) {
        if (src < 0 || dst < 0 || src >= pool_size_ || dst >= pool_size_) return false;
        llama_memory_seq_cp(mem_, src, dst, /*p0=*/0, /*p1=*/prefix_len);
        return true;
    }

    int   resident() const { return (int)map_.size(); }
    int   capacity() const { return pool_size_; }

private:
    struct Slot {
        llama_seq_id seq;
        uint32_t     uid;
        uint64_t     used;                         // logical clock for tie-breaks
        std::list<std::string>::iterator lru_it;   // position in lru_ (front = most recent)
    };

    static std::string make_key(uint32_t uid, const std::string& sid) {
        return std::to_string(uid) + "\x1f" + sid;   // uid-namespaced => isolation by key
    }

    void touch(Slot& slot) {
        slot.used = ++tick_;
        // move its key to the front of the LRU list
        lru_.splice(lru_.begin(), lru_, slot.lru_it);
        slot.lru_it = lru_.begin();
    }

    llama_seq_id evict_lru() {
        if (lru_.empty()) return kNone;
        const std::string victim_key = lru_.back();
        auto it = map_.find(victim_key);
        if (it == map_.end()) { lru_.pop_back(); return kNone; }
        const llama_seq_id seq = it->second.seq;
        llama_memory_seq_rm(mem_, seq, 0, -1);       // drop the victim's KV
        lru_.pop_back();
        map_.erase(it);
        return seq;                                   // reuse the freed slot immediately
    }

    llama_context* ctx_;
    llama_memory_t mem_;
    int            pool_size_;
    uint64_t       tick_ = 0;
    std::list<llama_seq_id> free_;                 // unused sequence ids
    std::list<std::string>  lru_;                  // keys, most-recent at front
    std::unordered_map<std::string, Slot> map_;    // key -> slot
};

} // namespace edgelm
