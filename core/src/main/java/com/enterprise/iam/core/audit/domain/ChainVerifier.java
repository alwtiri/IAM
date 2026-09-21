package com.enterprise.iam.core.audit.domain;

/**
 * Streaming verifier: feed events in ascending sequence order; the first mismatch is remembered.
 * Detects modified content (hash mismatch), removed or reordered events (sequence gap or broken link).
 */
public final class ChainVerifier {

    private byte[] expectedPrev = HashChain.GENESIS;
    private long expectedSeq = 1;
    private long checked;
    private Long firstBrokenSeq;
    private String problem;

    public void accept(AuditEvent e) {
        if (firstBrokenSeq != null) {
            return;
        }
        checked++;
        if (e.seq() != expectedSeq) {
            fail(e.seq(), "sequence gap: expected " + expectedSeq + " but found " + e.seq());
            return;
        }
        if (!HashChain.same(e.prevHash(), expectedPrev)) {
            fail(e.seq(), "broken link: previous hash does not match");
            return;
        }
        byte[] recomputed = HashChain.hash(e.prevHash(), e, e.seq());
        if (!HashChain.same(recomputed, e.hash())) {
            fail(e.seq(), "content hash mismatch (event modified)");
            return;
        }
        expectedPrev = e.hash();
        expectedSeq++;
    }

    /** Checks that the chain head agrees with the last verified event (detects truncation of the tail). */
    public void acceptHead(ChainHead head) {
        if (firstBrokenSeq == null && head.lastSeq() != expectedSeq - 1) {
            fail(expectedSeq, "chain head at " + head.lastSeq() + " but events end at " + (expectedSeq - 1));
        } else if (firstBrokenSeq == null && !HashChain.same(head.lastHash(), expectedPrev)) {
            fail(head.lastSeq(), "chain head hash does not match last event");
        }
    }

    private void fail(long seq, String why) {
        firstBrokenSeq = seq;
        problem = why;
    }

    public boolean valid() {
        return firstBrokenSeq == null;
    }

    public long checked() {
        return checked;
    }

    public Long firstBrokenSeq() {
        return firstBrokenSeq;
    }

    public String problem() {
        return problem;
    }
}
