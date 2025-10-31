package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.verification.database.VerifiableContacts
import com.mustafadakhel.kodex.verification.database.VerificationTokens
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

/**
 * Default implementation of VerificationService.
 *
 * Stores verification status per contact type in the database
 * and generates tokens for verification workflows.
 */
internal class DefaultVerificationService(
    private val config: VerificationConfig,
    private val timeZone: TimeZone
) : VerificationService {

    // === Contact Management ===

    override suspend fun setContact(userId: UUID, identifier: ContactIdentifier, value: String) {
        kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            // Check if contact exists with different value
            val existing = VerifiableContacts
                .select(
                    (VerifiableContacts.userId eq userId) and
                    (VerifiableContacts.contactType eq identifier.type) and
                    (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
                )
                .singleOrNull()

            val existingValue = existing?.get(VerifiableContacts.contactValue)
            val resetVerification = existingValue != null && existingValue != value

            VerifiableContacts.upsert {
                it[VerifiableContacts.userId] = userId
                it[VerifiableContacts.contactType] = identifier.type
                it[VerifiableContacts.customAttributeKey] = identifier.customAttributeKey
                it[VerifiableContacts.contactValue] = value
                it[VerifiableContacts.isVerified] = if (resetVerification) false else (existing?.get(VerifiableContacts.isVerified) ?: false)
                it[VerifiableContacts.verifiedAt] = if (resetVerification) null else existing?.get(VerifiableContacts.verifiedAt)
                it[VerifiableContacts.updatedAt] = now
            }
        }
    }

    override suspend fun removeContact(userId: UUID, identifier: ContactIdentifier) {
        kodexTransaction {
            VerifiableContacts.deleteWhere {
                (VerifiableContacts.userId eq userId) and
                (contactType eq identifier.type) and
                (customAttributeKey eq identifier.customAttributeKey)
            }

            // Also delete any tokens for this contact
            VerificationTokens.deleteWhere {
                (VerificationTokens.userId eq userId) and
                (contactType eq identifier.type) and
                (customAttributeKey eq identifier.customAttributeKey)
            }
        }
    }

    override fun getContact(userId: UUID, identifier: ContactIdentifier): ContactVerification? {
        return kodexTransaction {
            VerifiableContacts
                .select(
                    (VerifiableContacts.userId eq userId) and
                    (VerifiableContacts.contactType eq identifier.type) and
                    (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
                )
                .singleOrNull()
                ?.let {
                    ContactVerification(
                        identifier = identifier,
                        contactValue = it[VerifiableContacts.contactValue],
                        isVerified = it[VerifiableContacts.isVerified],
                        verifiedAt = it[VerifiableContacts.verifiedAt]
                    )
                }
        }
    }

    override fun getUserContacts(userId: UUID): List<ContactVerification> {
        return kodexTransaction {
            VerifiableContacts
                .select(VerifiableContacts.userId eq userId)
                .map {
                    val type = it[VerifiableContacts.contactType]
                    val attrKey = it[VerifiableContacts.customAttributeKey]
                    val identifier = ContactIdentifier(type, attrKey)

                    ContactVerification(
                        identifier = identifier,
                        contactValue = it[VerifiableContacts.contactValue],
                        isVerified = it[VerifiableContacts.isVerified],
                        verifiedAt = it[VerifiableContacts.verifiedAt]
                    )
                }
        }
    }

    // === Verification Status ===

    override fun isContactVerified(userId: UUID, identifier: ContactIdentifier): Boolean {
        return getContact(userId, identifier)?.isVerified ?: false
    }

    override fun canLogin(userId: UUID): Boolean {
        val requiredContacts = config.getRequiredContacts()

        if (requiredContacts.isEmpty()) {
            // No verification required
            return true
        }

        // Check if all required contacts are verified
        return requiredContacts.all { identifier ->
            isContactVerified(userId, identifier)
        }
    }

    override fun getStatus(userId: UUID): UserVerificationStatus {
        val contacts = getUserContacts(userId)
        val contactsMap = contacts.associateBy { it.identifier.key }
        return UserVerificationStatus(userId, contactsMap)
    }

    override fun getMissingVerifications(userId: UUID): List<ContactIdentifier> {
        val requiredContacts = config.getRequiredContacts()
        return requiredContacts.filter { identifier ->
            !isContactVerified(userId, identifier)
        }
    }

    // === Token Operations ===

    override suspend fun sendVerification(
        userId: UUID,
        identifier: ContactIdentifier
    ): String {
        val contact = getContact(userId, identifier)
            ?: error("Contact not found for user $userId: ${identifier.key}")

        // Get registered sender from config
        val sender = config.getSender(identifier)
            ?: error("No sender configured for contact type: ${identifier.key}")

        val token = generateToken()
        storeToken(userId, identifier, token)

        // Call registered sender implementation
        sender.send(contact.contactValue, token)

        return token
    }

    override suspend fun verifyToken(userId: UUID, identifier: ContactIdentifier, token: String): Boolean {
        return kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            val tokenRecord = VerificationTokens
                .select(
                    (VerificationTokens.userId eq userId) and
                    (VerificationTokens.token eq token) and
                    (VerificationTokens.contactType eq identifier.type) and
                    (VerificationTokens.customAttributeKey eq identifier.customAttributeKey) and
                    (VerificationTokens.expiresAt greater now) and
                    VerificationTokens.usedAt.isNull()
                )
                .singleOrNull()

            if (tokenRecord != null) {
                // Mark token as used
                VerificationTokens.update({ VerificationTokens.token eq token }) {
                    it[VerificationTokens.usedAt] = now
                }

                // Mark contact as verified
                setVerified(userId, identifier, true)

                true
            } else {
                false
            }
        }
    }

    override suspend fun resendVerification(
        userId: UUID,
        identifier: ContactIdentifier
    ) {
        // Invalidate existing tokens for this contact
        kodexTransaction {
            VerificationTokens.deleteWhere {
                (VerificationTokens.userId eq userId) and
                (contactType eq identifier.type) and
                (customAttributeKey eq identifier.customAttributeKey)
            }
        }

        // Send new verification
        sendVerification(userId, identifier)
    }

    // === Manual Control ===

    override fun setVerified(userId: UUID, identifier: ContactIdentifier, verified: Boolean) {
        kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            VerifiableContacts.update({
                (VerifiableContacts.userId eq userId) and
                (VerifiableContacts.contactType eq identifier.type) and
                (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
            }) {
                it[VerifiableContacts.isVerified] = verified
                it[VerifiableContacts.verifiedAt] = if (verified) now else null
                it[VerifiableContacts.updatedAt] = now
            }
        }
    }

    // === Private Helpers ===

    private fun generateToken(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private fun storeToken(userId: UUID, identifier: ContactIdentifier, token: String) {
        kodexTransaction {
            val now = Clock.System.now()
            // Use per-contact expiration, falling back to default
            val expiration = config.getTokenExpiration(identifier)
            val expiresAt = (now + expiration).toLocalDateTime(timeZone)

            VerificationTokens.insert {
                it[VerificationTokens.userId] = userId
                it[contactType] = identifier.type
                it[customAttributeKey] = identifier.customAttributeKey
                it[VerificationTokens.token] = token
                it[VerificationTokens.expiresAt] = expiresAt
            }
        }
    }
}
