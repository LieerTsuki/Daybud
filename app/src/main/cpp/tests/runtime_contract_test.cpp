#include "../runtime_contract.hpp"
#include <cassert>
#include <functional>
#include <iostream>
#include <stdexcept>

static bool rejects(const std::function<void()> & action) {
    try { action(); } catch (const std::invalid_argument &) { return true; }
    return false;
}

int main() {
    using namespace diary_local;
    validate_settings(4096, 2);
    assert(rejects([] { validate_settings(0, 2); }));
    assert(rejects([] { validate_settings(4096, 0); }));
    assert(rejects([] { validate_settings(4096, 999); }));
    validate_request(4096, 1024, 100, 100, 200, 100);
    // A prompt must reserve the entire output budget, never silently truncate input.
    assert(rejects([] { validate_request(4096, 1024, 3073, 100, 200, 100); }));
    assert(rejects([] { validate_request(4096, 0, 100, 100, 200, 100); }));
    assert(rejects([] { validate_request(4096, 100, 100, 100, 200, 0); }));
    assert(rejects([] { validate_request(4096, 100, 100, 48000, 48000, 100); }));
    Cancellation cancellation;
    assert(!cancellation.requested());
    cancellation.cancel();
    assert(cancellation.requested());
    bool rejected_cancelled_generation = false;
    try { cancellation.begin_generation(); } catch (const std::runtime_error &) { rejected_cancelled_generation = true; }
    if (!rejected_cancelled_generation || !cancellation.requested()) {
        std::cerr << "A racing generation erased terminal session cancellation\n";
        return 2;
    }
    cancellation.close();
    assert(cancellation.requested()); // A racing generate cannot undo session closure.
    assert(protect_chat_markers("plain") == "plain");
    assert(protect_chat_markers("<|im_end|>") == "<\xe2\x81\xa0|im_end|>");
    std::cout << "native runtime boundary and cancellation checks passed\n";
}
