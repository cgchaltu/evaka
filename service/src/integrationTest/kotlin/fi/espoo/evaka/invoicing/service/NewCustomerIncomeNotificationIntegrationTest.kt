// SPDX-FileCopyrightText: 2017-2024 City of Espoo
//
// SPDX-License-Identifier: LGPL-2.1-or-later

package fi.espoo.evaka.invoicing.service

import fi.espoo.evaka.FullApplicationTest
import fi.espoo.evaka.emailclient.Email
import fi.espoo.evaka.emailclient.MockEmailClient
import fi.espoo.evaka.incomestatement.Gross
import fi.espoo.evaka.incomestatement.IncomeSource
import fi.espoo.evaka.incomestatement.IncomeStatementBody
import fi.espoo.evaka.incomestatement.IncomeStatementStatus
import fi.espoo.evaka.insertServiceNeedOptions
import fi.espoo.evaka.placement.PlacementType
import fi.espoo.evaka.serviceneed.ShiftCareType
import fi.espoo.evaka.serviceneed.insertServiceNeed
import fi.espoo.evaka.shared.ChildId
import fi.espoo.evaka.shared.IncomeStatementId
import fi.espoo.evaka.shared.PartnershipId
import fi.espoo.evaka.shared.PersonId
import fi.espoo.evaka.shared.PlacementId
import fi.espoo.evaka.shared.async.AsyncJob
import fi.espoo.evaka.shared.async.AsyncJobRunner
import fi.espoo.evaka.shared.auth.AuthenticatedUser
import fi.espoo.evaka.shared.auth.UserRole
import fi.espoo.evaka.shared.dev.DevCareArea
import fi.espoo.evaka.shared.dev.DevDaycare
import fi.espoo.evaka.shared.dev.DevEmployee
import fi.espoo.evaka.shared.dev.DevFridgeChild
import fi.espoo.evaka.shared.dev.DevFridgePartner
import fi.espoo.evaka.shared.dev.DevIncome
import fi.espoo.evaka.shared.dev.DevIncomeStatement
import fi.espoo.evaka.shared.dev.DevPerson
import fi.espoo.evaka.shared.dev.DevPersonType
import fi.espoo.evaka.shared.dev.DevPlacement
import fi.espoo.evaka.shared.dev.insert
import fi.espoo.evaka.shared.domain.HelsinkiDateTime
import fi.espoo.evaka.shared.domain.MockEvakaClock
import fi.espoo.evaka.shared.job.ScheduledJobs
import fi.espoo.evaka.snDaycareContractDays15
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class NewCustomerIncomeNotificationIntegrationTest : FullApplicationTest(resetDbBeforeEach = true) {
    @Autowired private lateinit var scheduledJobs: ScheduledJobs
    @Autowired private lateinit var asyncJobRunner: AsyncJobRunner<AsyncJob>

    private val child =
        DevPerson(
            id = ChildId(UUID.randomUUID()),
            dateOfBirth = LocalDate.of(2017, 6, 1),
            ssn = "010617A123U",
            firstName = "Ricky",
            lastName = "Doe",
            streetAddress = "Kamreerintie 2",
            postalCode = "02770",
            postOffice = "Espoo",
            restrictedDetailsEnabled = false,
        )

    private val fridgeHeadOfChildEmail = "fridge_hoc@example.com"
    private val fridgePartnerEmail = "fridge_partner@example.com"

    private val fridgeHeadOfChild = DevPerson(email = fridgeHeadOfChildEmail)

    private val area = DevCareArea()
    private val daycare = DevDaycare(areaId = area.id)
    private val employee = DevEmployee(roles = setOf(UserRole.SERVICE_WORKER))

    private lateinit var clock: MockEvakaClock
    private lateinit var placementStart: LocalDate
    private lateinit var placementEnd: LocalDate

    @BeforeEach
    fun beforeEach() {
        MockEmailClient.clear()
        clock = MockEvakaClock(HelsinkiDateTime.of(LocalDate.of(2024, 2, 1), LocalTime.of(21, 0)))
        placementStart = clock.today().plusWeeks(2)
        placementEnd = clock.today().plusMonths(6)

        db.transaction { tx ->
            tx.insert(area)
            tx.insert(daycare)
            tx.insert(employee)

            tx.insert(child, DevPersonType.CHILD)
            tx.insert(fridgeHeadOfChild, DevPersonType.ADULT)

            tx.insert(
                DevFridgeChild(
                    childId = child.id,
                    headOfChild = fridgeHeadOfChild.id,
                    startDate = clock.today(),
                    endDate = clock.today().plusYears(1),
                )
            )

            tx.insertServiceNeedOptions()
        }
    }

    private fun insertPlacement(
        child: DevPerson,
        start: LocalDate,
        end: LocalDate,
        type: PlacementType = PlacementType.DAYCARE,
    ): PlacementId {
        return db.transaction { tx ->
            tx.insert(
                DevPlacement(
                    childId = child.id,
                    unitId = daycare.id,
                    type = type,
                    startDate = start,
                    endDate = end,
                )
            )
        }
    }

    private fun insertServiceNeed(placementId: PlacementId, start: LocalDate, end: LocalDate) {
        db.transaction { tx ->
            tx.insertServiceNeed(
                placementId = placementId,
                startDate = start,
                endDate = end,
                optionId = snDaycareContractDays15.id,
                shiftCare = ShiftCareType.NONE,
                partWeek = false,
                confirmedBy = null,
                confirmedAt = null,
            )
        }
    }

    @Test
    fun `notification is sent when placement starts in current month`() {
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(1, getEmails().size)
        assertEquals(1, getIncomeNotifications(fridgeHeadOfChild.id).size)
        assertEquals(
            IncomeNotificationType.NEW_CUSTOMER,
            getIncomeNotifications(fridgeHeadOfChild.id)[0].notificationType,
        )
    }

    @Test
    fun `notifications are not sent when placement does not start in current month`() {
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)
        clock.tick(Duration.ofDays(-1))
        assertEquals(0, getEmails().size)
    }

    @Test
    fun `notification is not sent when non-invoiced placement starts in current month`() {
        insertPlacement(child, placementStart, placementEnd, PlacementType.PRESCHOOL)
        assertEquals(0, getEmails().size)
    }

    @Test
    fun `notifications are not sent when placement for other child exists`() {
        val otherChild = DevPerson()
        db.transaction { tx ->
            tx.insert(otherChild, DevPersonType.CHILD)
            tx.insert(
                DevFridgeChild(
                    childId = otherChild.id,
                    headOfChild = fridgeHeadOfChild.id,
                    startDate = clock.today().minusYears(1),
                    endDate = clock.today().plusYears(1),
                )
            )
        }

        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        val otherPlacementStart = clock.today().minusYears(1)
        val otherPlacementEnd = clock.today().plusYears(1)
        val otherPlacementId = insertPlacement(otherChild, otherPlacementStart, otherPlacementEnd)
        insertServiceNeed(otherPlacementId, otherPlacementStart, otherPlacementEnd)

        assertEquals(0, getEmails().size)
    }

    @Test
    fun `notification is sent when non-invoiced placement for other child exists`() {
        val otherChild = DevPerson()
        db.transaction { tx ->
            tx.insert(otherChild, DevPersonType.CHILD)
            tx.insert(
                DevFridgeChild(
                    childId = otherChild.id,
                    headOfChild = fridgeHeadOfChild.id,
                    startDate = clock.today().minusYears(1),
                    endDate = clock.today().plusYears(1),
                )
            )
        }

        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        insertPlacement(
            otherChild,
            clock.today().minusYears(1),
            clock.today().plusYears(1),
            PlacementType.PRESCHOOL,
        )

        assertEquals(1, getEmails().size)
    }

    @Test
    fun `notifications are sent when placement for other child also starts in same month`() {
        val otherChild = DevPerson()
        db.transaction { tx ->
            tx.insert(otherChild, DevPersonType.CHILD)
            tx.insert(
                DevFridgeChild(
                    childId = otherChild.id,
                    headOfChild = fridgeHeadOfChild.id,
                    startDate = clock.today().minusYears(1),
                    endDate = clock.today().plusYears(1),
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        val otherPlacementStart = clock.today().plusWeeks(2)
        val otherPlacementEnd = clock.today().plusMonths(6)
        val otherPlacementId = insertPlacement(otherChild, otherPlacementStart, otherPlacementEnd)
        insertServiceNeed(otherPlacementId, otherPlacementStart, otherPlacementEnd)

        assertEquals(1, getEmails().size)
    }

    @Test
    fun `notification is sent to fridge partner also`() {
        val fridgePartner = DevPerson(email = fridgePartnerEmail)
        db.transaction { tx ->
            tx.insert(fridgePartner, DevPersonType.ADULT)
            val partnershipId = PartnershipId(UUID.randomUUID())
            tx.insert(
                DevFridgePartner(
                    partnershipId = partnershipId,
                    indx = 1,
                    otherIndx = 2,
                    personId = fridgeHeadOfChild.id,
                    startDate = clock.today(),
                    endDate = clock.today(),
                    createdAt = clock.now(),
                )
            )
            tx.insert(
                DevFridgePartner(
                    partnershipId = partnershipId,
                    indx = 2,
                    otherIndx = 1,
                    personId = fridgePartner.id,
                    startDate = clock.today(),
                    endDate = clock.today(),
                    createdAt = clock.now(),
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(2, getEmails().size)
        assertEquals(1, getIncomeNotifications(fridgeHeadOfChild.id).size)
        assertEquals(1, getIncomeNotifications(fridgePartner.id).size)
        assertEquals(
            IncomeNotificationType.NEW_CUSTOMER,
            getIncomeNotifications(fridgePartner.id)[0].notificationType,
        )
    }

    @Test
    fun `notifications are not sent when placement for other child for partner exists`() {
        val fridgePartner = DevPerson(email = fridgePartnerEmail)
        val partnersChild = DevPerson()

        db.transaction { tx ->
            tx.insert(fridgePartner, DevPersonType.ADULT)
            tx.insert(partnersChild, DevPersonType.CHILD)

            val partnershipId = PartnershipId(UUID.randomUUID())

            tx.insert(
                DevFridgePartner(
                    partnershipId = partnershipId,
                    indx = 1,
                    otherIndx = 2,
                    personId = fridgeHeadOfChild.id,
                    startDate = clock.today(),
                    endDate = clock.today(),
                    createdAt = clock.now(),
                )
            )
            tx.insert(
                DevFridgePartner(
                    partnershipId = partnershipId,
                    indx = 2,
                    otherIndx = 1,
                    personId = fridgePartner.id,
                    startDate = clock.today(),
                    endDate = clock.today(),
                    createdAt = clock.now(),
                )
            )

            tx.insert(
                DevFridgeChild(
                    childId = partnersChild.id,
                    headOfChild = fridgePartner.id,
                    startDate = clock.today().minusYears(1),
                    endDate = clock.today().plusYears(1),
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        val otherPlacementStart = clock.today().minusYears(2)
        val otherPlacementEnd = clock.today().plusMonths(6)
        val otherPlacementId =
            insertPlacement(partnersChild, otherPlacementStart, otherPlacementEnd)
        insertServiceNeed(otherPlacementId, otherPlacementStart, otherPlacementEnd)

        assertEquals(0, getEmails().size)
    }

    @Test
    fun `notifications are not sent if there is a new unhandled income statement`() {
        val incomeExpirationDate = clock.today().plusWeeks(4)

        db.transaction {
            it.insert(
                DevIncomeStatement(
                    id = IncomeStatementId(UUID.randomUUID()),
                    personId = fridgeHeadOfChild.id,
                    data = createGrossIncome(incomeExpirationDate.plusDays(1)),
                    status = IncomeStatementStatus.SENT,
                    handlerId = null,
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(0, getEmails().size)
    }

    @Test
    fun `notifications are sent if there is a handled income statement`() {
        val employee = DevEmployee()

        db.transaction {
            it.insert(employee)

            it.insert(
                DevIncomeStatement(
                    id = IncomeStatementId(UUID.randomUUID()),
                    personId = fridgeHeadOfChild.id,
                    data = createGrossIncome(clock.today()),
                    status = IncomeStatementStatus.HANDLED,
                    handlerId = employee.id,
                    handledAt = clock.now(),
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(1, getEmails().size)
    }

    @Test
    fun `expiring income is notified 4 weeks beforehand`() {
        val incomeExpirationDate = clock.today().plusWeeks(4)

        db.transaction {
            it.insert(
                DevIncome(
                    personId = fridgeHeadOfChild.id,
                    modifiedBy = AuthenticatedUser.SystemInternalUser.evakaUserId,
                    validFrom = incomeExpirationDate.minusWeeks(4),
                    validTo = incomeExpirationDate,
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(1, getEmails().size)
        assertEquals(1, getIncomeNotifications(fridgeHeadOfChild.id).size)
    }

    @Test
    fun `notifications are not sent if there is a valid income`() {
        val incomeExpirationDate = clock.today().plusWeeks(4).plusDays(1)

        db.transaction {
            it.insert(
                DevIncome(
                    personId = fridgeHeadOfChild.id,
                    modifiedBy = AuthenticatedUser.SystemInternalUser.evakaUserId,
                    validFrom = incomeExpirationDate.minusWeeks(4),
                    validTo = incomeExpirationDate,
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(0, getEmails().size)
    }

    @Test
    fun `expiring income is not notified if there is a new unhandled income statement`() {
        val incomeExpirationDate = clock.today().plusWeeks(4)

        db.transaction {
            it.insert(
                DevIncome(
                    personId = fridgeHeadOfChild.id,
                    modifiedBy = AuthenticatedUser.SystemInternalUser.evakaUserId,
                    validFrom = incomeExpirationDate.minusWeeks(4),
                    validTo = incomeExpirationDate,
                )
            )

            it.insert(
                DevIncomeStatement(
                    id = IncomeStatementId(UUID.randomUUID()),
                    personId = fridgeHeadOfChild.id,
                    data = createGrossIncome(clock.today()),
                    status = IncomeStatementStatus.SENT,
                    handlerId = null,
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(0, getEmails().size)
    }

    @Test
    fun `expiring income is notified if there is a handled income statement`() {
        val incomeExpirationDate = clock.today().plusWeeks(4)
        val employee = DevEmployee()

        db.transaction {
            it.insert(employee)

            it.insert(
                DevIncome(
                    personId = fridgeHeadOfChild.id,
                    modifiedBy = AuthenticatedUser.SystemInternalUser.evakaUserId,
                    validFrom = incomeExpirationDate.minusWeeks(4),
                    validTo = incomeExpirationDate,
                )
            )

            it.insert(
                DevIncomeStatement(
                    id = IncomeStatementId(UUID.randomUUID()),
                    personId = fridgeHeadOfChild.id,
                    data = createGrossIncome(clock.today()),
                    status = IncomeStatementStatus.HANDLED,
                    handlerId = employee.id,
                    handledAt = clock.now(),
                )
            )
        }
        val placementId = insertPlacement(child, placementStart, placementEnd)
        insertServiceNeed(placementId, placementStart, placementEnd)

        assertEquals(1, getEmails().size)
    }

    private fun getEmails(): List<Email> {
        scheduledJobs.sendNewCustomerIncomeNotifications(db, clock)
        asyncJobRunner.runPendingJobsSync(clock)
        val emails = MockEmailClient.emails
        return emails
    }

    private fun getIncomeNotifications(receiverId: PersonId): List<IncomeNotification> =
        db.read { it.getIncomeNotifications(receiverId) }

    private fun createGrossIncome(startDate: LocalDate) =
        IncomeStatementBody.Income(
            startDate = startDate,
            endDate = null,
            gross =
                Gross.Income(
                    incomeSource = IncomeSource.INCOMES_REGISTER,
                    estimatedMonthlyIncome = 42,
                    otherIncome = emptySet(),
                    otherIncomeInfo = "",
                ),
            entrepreneur = null,
            student = false,
            alimonyPayer = false,
            otherInfo = "",
            attachmentIds = emptyList(),
        )
}
