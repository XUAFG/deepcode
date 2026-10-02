"""Pinned client selection repair: retain a selection masked by a reconnect gap."""
import hashlib

BASE_SHA = '181e4162bb1f533926854223fb5552ac88b39a7b2c8233a9bc51d4a20385524e'
# 0.2.0-rc.2 (dsh-mobile-apk v0.14.3): the session-selection area was rewritten
# upstream and the `persisted !== void 0` reset site no longer exists. The
# reconnect-gap semantics are owned by the new implementation, so the overlay
# is a deliberate no-op on this source; re-audit if the anchor ever returns.
UPSTREAM_020_SHA = 'c3846207f2b57161d2b832d7af79cce7ec5cdb66ddb0f3efb13e87f4511116fa'
OLD = 'if (persisted !== void 0) this.selection.set({});'
NEW = 'if (this.manager.selected === void 0 && persisted !== void 0) this.selection.set({});'


def patch_session_selection(raw: bytes) -> bytes:
    digest = hashlib.sha256(raw).hexdigest()
    if digest == UPSTREAM_020_SHA:
        return raw
    if digest != BASE_SHA:
        raise ValueError('Unknown session-controller client; review upstream selection semantics first')
    text = raw.decode()
    if text.count(OLD) != 1:
        raise ValueError('Session selection patch anchor mismatch')
    return text.replace(OLD, NEW).encode()
