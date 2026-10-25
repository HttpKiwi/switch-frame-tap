/*
 * v0.7.20: a controller on the viewer's computer plays the console.
 *
 * config viewer_input = 1 (arm word "vinput", off by default). The viewer
 * sends its controller's state ("SFTI", applet_mitm_viewermsg.hpp) over USB
 * or the network; here it becomes a virtual Pro Controller (hid:dbg, the
 * HDLS devices sys-botbase and sys-con use), attached at the first message
 * and let go when the viewer leaves, says it has no controller, or goes
 * quiet. Viewers send only to a console whose stream headers carry
 * HdrFlagInput, so a console with this off never gets a message.
 */
#pragma once
#include <stratosphere.hpp>
#include "applet_mitm_viewermsg.hpp"

namespace ams::mitm::applet {

    /* config viewer_input; read at boot and when the manager saves */
    extern std::atomic<bool> g_vpad_enabled;

    /* stream header flag bit 7: this console takes the viewer's controller */
    constexpr u16 HdrFlagInput = 128;
    inline u16 VPadHdrFlag() { return g_vpad_enabled.load(std::memory_order_relaxed) ? HdrFlagInput : 0; }

    /* a viewer's controller state (any thread) */
    void VPadFromViewer(const sft::viewermsg::PadMsg &m);
    /* every ~100-250 ms from the threads that read viewers: everything let go
     * after 0.5 s without a message, the controller detached after 3 s */
    void VPadTick();
    /* the viewer left: detach now */
    void VPadRelease(const char *why);

}
