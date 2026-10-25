/*
 * v0.7.20: the viewer's controller as a virtual Pro Controller - see
 * applet_mitm_vpad.hpp.
 *
 * hid:dbg's HDLS calls (libnx hiddbg.c): a work buffer lent to hid as
 * transfer memory (one page; static data can be lent, shared memory can't),
 * then a device and its state. From 13.0.0 each client has a session of its
 * own, so sys-con or sys-botbase can use HDLS beside us. Nothing here is
 * opened until a message comes, and all of it is closed when the viewer
 * goes: with viewer_input off, hid:dbg is never touched.
 */
#include "applet_mitm_vpad.hpp"
#include "applet_mitm_log.hpp"
#include "applet_mitm_sm.hpp"

namespace ams::mitm::applet {

    constinit std::atomic<bool> g_vpad_enabled{false};

    namespace {

        constexpr u64 NeutralNs = UINT64_C(500000000);    /* no message for 0.5 s: let go of everything */
        constexpr u64 DetachNs  = UINT64_C(3000000000);   /* 3 s: the viewer is gone (it sends every 100 ms) */
        constexpr u64 RetryNs   = UINT64_C(5000000000);   /* a failed attach: not again before 5 s */

        constinit os::SdkMutex g_lock;
        alignas(0x1000) constinit u8 g_work[0x1000];
        constinit bool g_open = false;
        constinit HiddbgHdlsSessionId g_session = {};
        constinit HiddbgHdlsHandle g_handle = {};
        constinit HiddbgHdlsState g_state = {};
        constinit bool g_neutral = true;
        constinit u64 g_last_ns = 0;
        constinit u64 g_retry_ns = 0;
        constinit u32 g_msgs = 0, g_sets = 0;

        u64 NowNs() { return armTicksToNs(armGetSystemTick()); }

        constexpr u32 Rgb(u32 r, u32 g, u32 b) { return r | (g << 8) | (b << 16) | (0xFFu << 24); }

        void CloseLocked(const char *why) {
            if (!g_open) { return; }
            const ::Result rd = hiddbgDetachHdlsVirtualDevice(g_handle);
            const ::Result rr = hiddbgReleaseHdlsWorkBuffer(g_session);
            hiddbgExit();
            g_open = false;
            LogLine("vpad: the viewer's controller is gone (%s) - %u messages, %u changes (detach rc=0x%x, release rc=0x%x)",
                    why, g_msgs, g_sets, rd, rr);
        }

        bool OpenLocked() {
            if (g_open) { return true; }
            const u64 now = NowNs();
            if (now < g_retry_ns) { return false; }
            ::Result rc;
            {
                SmUse sm_hiddbg;
                rc = hiddbgInitialize();
            }
            ::Result rw = 0, ra = 0;
            if (R_SUCCEEDED(rc)) {
                rw = hiddbgAttachHdlsWorkBuffer(std::addressof(g_session), g_work, sizeof(g_work));
                if (R_SUCCEEDED(rw)) {
                    HiddbgHdlsDeviceInfo info = {};
                    info.deviceType = HidDeviceType_FullKey3;                 /* a Pro Controller */
                    info.npadInterfaceType = HidNpadInterfaceType_Bluetooth;
                    info.singleColorBody = Rgb(0x2D, 0x2D, 0x2D);
                    info.singleColorButtons = Rgb(0xE6, 0xE6, 0xE6);
                    info.colorLeftGrip = Rgb(0x2D, 0x2D, 0x2D);
                    info.colorRightGrip = Rgb(0x2D, 0x2D, 0x2D);
                    ra = hiddbgAttachHdlsVirtualDevice(std::addressof(g_handle), std::addressof(info));
                    if (R_FAILED(ra)) { static_cast<void>(hiddbgReleaseHdlsWorkBuffer(g_session)); }
                }
                if (R_FAILED(rw) || R_FAILED(ra)) { hiddbgExit(); }
            }
            LogLine("vpad: the viewer's controller %s (hid:dbg rc=0x%x, work buffer rc=0x%x, device rc=0x%x)",
                    R_SUCCEEDED(rc) && R_SUCCEEDED(rw) && R_SUCCEEDED(ra) ? "attached as a Pro Controller" : "could not attach",
                    rc, rw, ra);
            if (R_FAILED(rc) || R_FAILED(rw) || R_FAILED(ra)) { g_retry_ns = now + RetryNs; return false; }
            g_open = true;
            g_msgs = g_sets = 0;
            g_state = {};
            g_state.battery_level = 4;    /* full */
            g_neutral = true;
            return true;
        }

        bool SetLocked(const HiddbgHdlsState &s) {
            const ::Result rc = hiddbgSetHdlsState(g_handle, std::addressof(s));
            if (R_FAILED(rc)) {
                /* the system dropped it (sleep, a controller change) - the next
                 * message attaches again */
                LogLine("vpad: SetHdlsState rc=0x%x", rc);
                CloseLocked("hid refused its state");
                return false;
            }
            g_state = s;
            ++g_sets;
            return true;
        }

    }

    void VPadFromViewer(const sft::viewermsg::PadMsg &m) {
        std::scoped_lock lk(g_lock);
        if (!g_vpad_enabled.load(std::memory_order_relaxed)) { CloseLocked("viewer_input is off"); return; }
        g_last_ns = NowNs();
        if ((m.flags & sft::viewermsg::PadOn) == 0) { CloseLocked("the viewer has no controller now"); return; }
        if (!OpenLocked()) { return; }
        ++g_msgs;
        HiddbgHdlsState s = g_state;
        s.buttons = m.buttons & sft::viewermsg::PadButtons;
        s.analog_stick_l = { sft::viewermsg::ClampStick(m.lx), sft::viewermsg::ClampStick(m.ly) };
        s.analog_stick_r = { sft::viewermsg::ClampStick(m.rx), sft::viewermsg::ClampStick(m.ry) };
        if (s.buttons == g_state.buttons &&
            s.analog_stick_l.x == g_state.analog_stick_l.x && s.analog_stick_l.y == g_state.analog_stick_l.y &&
            s.analog_stick_r.x == g_state.analog_stick_r.x && s.analog_stick_r.y == g_state.analog_stick_r.y) {
            return;    /* a keepalive: hid keeps the last state */
        }
        if (SetLocked(s)) {
            g_neutral = s.buttons == 0 && s.analog_stick_l.x == 0 && s.analog_stick_l.y == 0 &&
                        s.analog_stick_r.x == 0 && s.analog_stick_r.y == 0;
        }
    }

    void VPadTick() {
        std::scoped_lock lk(g_lock);
        if (!g_open) { return; }
        if (!g_vpad_enabled.load(std::memory_order_relaxed)) { CloseLocked("viewer_input was turned off"); return; }
        const u64 age = NowNs() - g_last_ns;
        if (age >= DetachNs) { CloseLocked("no message for 3 s"); return; }
        if (age >= NeutralNs && !g_neutral) {
            /* a stalled connection must not hold a button down */
            HiddbgHdlsState s = g_state;
            s.buttons = 0;
            s.analog_stick_l = {};
            s.analog_stick_r = {};
            if (SetLocked(s)) { g_neutral = true; }
        }
    }

    void VPadRelease(const char *why) {
        std::scoped_lock lk(g_lock);
        CloseLocked(why);
    }

}
