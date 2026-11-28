package com.storagehub.service;

import com.storagehub.domain.model.*;
import jakarta.persistence.criteria.*;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/** Business queue order is part of the database query, never a sort of one fetched page. */
public final class BusinessQueueOrder {
    private BusinessQueueOrder() {}

    public static Specification<Rental> rentals(boolean manager) {
        return (r, q, c) -> {
            if (q.getResultType() != Rental.class) return c.conjunction();
            var work = q.subquery(Integer.class);
            var returns = work.from(ReturnCase.class);
            work.select(c.literal(1)).where(c.equal(returns.get("rental"), r),
                manager ? c.or(c.equal(returns.get("status"), ReturnCaseStatus.disputed),
                    c.and(c.equal(returns.get("status"), ReturnCaseStatus.awaiting_business_review), c.equal(returns.get("returnType"), ReturnType.INVENTORY_MISMATCH)))
                    : returns.get("status").in(ReturnCaseStatus.payment_due, ReturnCaseStatus.awaiting_customer_confirmation));
            var due = q.subquery(Instant.class);
            var pendingReturn = due.from(ReturnCase.class);
            due.select(c.least(pendingReturn.<Instant>get("settlementPaymentDueAt"))).where(
                c.equal(pendingReturn.get("rental"), r), c.equal(pendingReturn.get("status"), ReturnCaseStatus.payment_due));
            var waiting = q.subquery(Instant.class);
            var waitingReturn = waiting.from(ReturnCase.class);
            waiting.select(c.least(c.coalesce(waitingReturn.<Instant>get("settlementPublishedAt"), waitingReturn.<Instant>get("createdAt"))))
                .where(c.equal(waitingReturn.get("rental"), r), manager
                    ? c.or(c.equal(waitingReturn.get("status"), ReturnCaseStatus.disputed),
                        c.and(c.equal(waitingReturn.get("status"), ReturnCaseStatus.awaiting_business_review), c.equal(waitingReturn.get("returnType"), ReturnType.INVENTORY_MISMATCH)))
                    : waitingReturn.get("status").in(ReturnCaseStatus.payment_due, ReturnCaseStatus.awaiting_customer_confirmation));
            var renewalWork = q.subquery(Integer.class);
            var renewal = renewalWork.from(Renewal.class);
            renewalWork.select(c.literal(1)).where(c.equal(renewal.get("rental"), r), manager
                ? c.equal(renewal.get("status"), RenewalStatus.pending)
                : renewal.get("status").in(RenewalStatus.accepted, RenewalStatus.approved, RenewalStatus.payment_failed));
            var finished = c.or(c.equal(r.get("status"), RentalStatus.completed), c.isNotNull(r.get("actualReturnedAt")));
            var group = c.<Integer>selectCase().when(c.or(c.exists(work), c.exists(renewalWork)), 0).when(finished, 3)
                .when(r.get("status").in(RentalStatus.active, RentalStatus.setup), 1).otherwise(2);
            // Persisted dates only: never let a comment/update reset a customer's place in the queue.
            var end = r.<java.time.LocalDate>get("contractEndDate");
            q.orderBy(c.asc(group), c.asc(c.<Integer>selectCase().when(c.isNull(due), 1).otherwise(0)), c.asc(due),
                c.asc(waiting), c.desc(c.<Instant>selectCase().when(finished, r.get("completedAt")).otherwise(c.nullLiteral(Instant.class))),
                c.asc(c.<Integer>selectCase().when(c.isNull(end), 1).otherwise(0)), c.asc(end), c.asc(r.get("createdAt")), c.asc(r.get("id")));
            return c.conjunction();
        };
    }

    public static Specification<Renewal> renewals(boolean manager) {
        return (r, q, c) -> {
            if (q.getResultType() != Renewal.class) return c.conjunction();
            var state = r.<RenewalStatus>get("status");
            var actionable = manager ? c.equal(state, RenewalStatus.pending)
                : state.in(RenewalStatus.accepted, RenewalStatus.payment_failed, RenewalStatus.approved);
            var closed = state.in(RenewalStatus.completed, RenewalStatus.cancelled, RenewalStatus.rejected, RenewalStatus.payment_expired);
            var group = c.<Integer>selectCase().when(actionable, 0).when(closed, 2).otherwise(1);
            var due = q.subquery(Instant.class);
            var workflow = due.from(com.storagehub.service.renewal.persistence.RenewalWorkflow.class);
            due.select(workflow.get("paymentDeadline")).where(c.equal(workflow.get("id"), r.get("id")));
            var end = r.get("rental").<java.time.LocalDate>get("contractEndDate");
            q.orderBy(c.asc(group), c.asc(c.<Integer>selectCase().when(c.isNull(due), 1).otherwise(0)), c.asc(due),
                c.asc(c.<Integer>selectCase().when(c.isNull(end), 1).otherwise(0)),
                c.asc(end), c.asc(r.get("createdAt")), c.asc(r.get("id")));
            return c.conjunction();
        };
    }
}
