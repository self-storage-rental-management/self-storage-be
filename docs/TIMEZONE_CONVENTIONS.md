# Timezones in renewal, overdue and customer support

The renewal, overdue, renewal installment/fee and communication policy services use `com.storagehub.time.BusinessTime.ZONE` (`Asia/Ho_Chi_Minh`). Other flows retain their existing implementation.

- Store and compare absolute timestamps as `Instant`. UTC storage does not change the business calendar.
- Convert an instant with `atZone(BusinessTime.ZONE)` before deriving business dates, months, appointment hours, overdue days or record-code periods.
- Use `LocalDate.now(BusinessTime.ZONE)` and `YearMonth.now(BusinessTime.ZONE)` when a service needs today's calendar date/month. Services with an injected Clock should continue deriving the date from that clock's instant in the business zone.
- Keep date-only request fields as `LocalDate`.
- Do not change the JVM default timezone to implement business rules. Test with `-Duser.timezone=UTC` and `-Duser.timezone=Asia/Ho_Chi_Minh`.

This change requires no database migration or API timestamp format change.

Changes to unrelated services, reporting tests and the global CI matrix were reverted. Test the scoped features under both UTC and Vietnam JVM timezones; CI retains its original configuration.
