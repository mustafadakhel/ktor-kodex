package com.mustafadakhel.kodex.event

import com.mustafadakhel.kodex.extension.ExtensionRegistry
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.Collections
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

class EventBusTest : FunSpec({

    // Helper function to create EventBus with subscribers properly registered
    fun createEventBusWithSubscribers(vararg subscribers: EventSubscriber<*>): EventBus {
        if (subscribers.isEmpty()) {
            return DefaultEventBus(ExtensionRegistry.empty())
        }

        val provider = object : com.mustafadakhel.kodex.extension.EventSubscriberProvider {
            override fun getEventSubscribers(): List<EventSubscriber<*>> = subscribers.toList()
        }

        val registry = com.mustafadakhel.kodex.extension.ExtensionRegistry.from(
            mapOf(com.mustafadakhel.kodex.extension.EventSubscriberProvider::class to provider)
        )

        return DefaultEventBus(registry)
    }

    // Test event classes
    data class TestUserEvent(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val action: String
    ) : KodexEvent {
        override val eventType: String = "TEST_USER_EVENT"
    }

    data class TestSecurityEvent(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val severity: EventSeverity
    ) : KodexEvent {
        override val eventType: String = "TEST_SECURITY_EVENT"
    }

    context("Basic Event Publishing and Subscription") {
        test("should deliver event to single subscriber") {
            val receivedEvents = mutableListOf<TestUserEvent>()

            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    receivedEvents.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(subscriber)

            val event = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            runBlocking {
                eventBus.publish(event)
                delay(300) // Allow async processing
            }

            receivedEvents.size shouldBe 1
            receivedEvents[0] shouldBe event
        }

        test("should deliver event to multiple subscribers of same type") {
            val subscriber1Events = mutableListOf<TestUserEvent>()
            val subscriber2Events = mutableListOf<TestUserEvent>()

            val subscriber1 = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    subscriber1Events.add(event)
                }
            }

            val subscriber2 = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    subscriber2Events.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(subscriber1, subscriber2)

            val event = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "UPDATE"
            )

            runBlocking {
                eventBus.publish(event)
                delay(300)
            }

            subscriber1Events.size shouldBe 1
            subscriber2Events.size shouldBe 1
            subscriber1Events[0] shouldBe event
            subscriber2Events[0] shouldBe event
        }

        test("should deliver multiple events to subscriber") {
            val receivedEvents = mutableListOf<TestUserEvent>()

            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    receivedEvents.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(subscriber)

            val event1 = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            val event2 = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "UPDATE"
            )

            runBlocking {
                eventBus.publish(event1)
                eventBus.publish(event2)
                delay(300)
            }

            receivedEvents.size shouldBe 2
            receivedEvents shouldContainExactlyInAnyOrder listOf(event1, event2)
        }

        test("should not deliver events to unsubscribed types") {
            val userEvents = mutableListOf<TestUserEvent>()
            val securityEvents = mutableListOf<TestSecurityEvent>()

            val userSubscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    userEvents.add(event)
                }
            }

            val securitySubscriber = object : EventSubscriber<TestSecurityEvent> {
                override val eventType: KClass<out TestSecurityEvent> = TestSecurityEvent::class
                override suspend fun onEvent(event: TestSecurityEvent) {
                    securityEvents.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(userSubscriber, securitySubscriber)

            val userEvent = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            val securityEvent = TestSecurityEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                severity = EventSeverity.CRITICAL
            )

            runBlocking {
                eventBus.publish(userEvent)
                eventBus.publish(securityEvent)
                delay(300)
            }

            userEvents.size shouldBe 1
            securityEvents.size shouldBe 1
            userEvents[0] shouldBe userEvent
            securityEvents[0] shouldBe securityEvent
        }
    }

    context("Subscriber Priority") {
        test("should execute subscribers in priority order") {
            val executionOrder = Collections.synchronizedList(mutableListOf<String>())

            val lowPrioritySubscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override val priority: Int = 1
                override suspend fun onEvent(event: TestUserEvent) {
                    delay(10) // Simulate work
                    executionOrder.add("low")
                }
            }

            val highPrioritySubscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override val priority: Int = 10
                override suspend fun onEvent(event: TestUserEvent) {
                    delay(10) // Simulate work
                    executionOrder.add("high")
                }
            }

            val mediumPrioritySubscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override val priority: Int = 5
                override suspend fun onEvent(event: TestUserEvent) {
                    delay(10) // Simulate work
                    executionOrder.add("medium")
                }
            }

            val eventBus = createEventBusWithSubscribers(
                lowPrioritySubscriber,
                highPrioritySubscriber,
                mediumPrioritySubscriber
            )

            val event = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            runBlocking {
                eventBus.publish(event)

                // Use eventually to wait for all subscribers with retries
                eventually(15.seconds) {
                    executionOrder.size shouldBe 3
                }
            }

            executionOrder shouldContain "high"
            executionOrder shouldContain "medium"
            executionOrder shouldContain "low"
        }
    }

    context("EventSubscriberProvider Auto-registration") {
        test("should auto-register subscribers from EventSubscriberProvider extensions") {
            val receivedEvents = Collections.synchronizedList(mutableListOf<TestUserEvent>())

            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    receivedEvents.add(event)
                }
            }

            val provider = object : com.mustafadakhel.kodex.extension.EventSubscriberProvider {
                override fun getEventSubscribers(): List<EventSubscriber<*>> = listOf(subscriber)
            }

            val registry = com.mustafadakhel.kodex.extension.ExtensionRegistry.from(
                mapOf(com.mustafadakhel.kodex.extension.EventSubscriberProvider::class to provider)
            )

            val eventBus = DefaultEventBus(registry)

            val event = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            runBlocking {
                eventBus.publish(event)
                eventually(5.seconds) {
                    receivedEvents.size shouldBe 1
                }
            }

            receivedEvents[0] shouldBe event
        }
    }

    context("Unsubscribe") {
        test("should not deliver events after unsubscribe") {
            val receivedEvents = mutableListOf<TestUserEvent>()

            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    receivedEvents.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(subscriber)

            val event1 = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            runBlocking {
                eventBus.publish(event1)
                delay(300)
            }

            receivedEvents.size shouldBe 1

            eventBus.unsubscribe(subscriber)

            val event2 = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "UPDATE"
            )

            runBlocking {
                eventBus.publish(event2)
                delay(300)
            }

            receivedEvents.size shouldBe 1
        }

        test("should handle unsubscribe of non-existent subscriber gracefully") {
            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {}
            }

            val eventBus = createEventBusWithSubscribers()

            // Unsubscribe without ever subscribing - should not throw
            eventBus.unsubscribe(subscriber)
        }
    }

    context("Subscriber Isolation") {
        test("should not propagate exceptions from one subscriber to others") {
            val subscriber1Events = mutableListOf<TestUserEvent>()
            val subscriber3Events = mutableListOf<TestUserEvent>()

            val subscriber1 = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    subscriber1Events.add(event)
                }
            }

            val subscriber2 = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    throw RuntimeException("Subscriber 2 failed!")
                }
            }

            val subscriber3 = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    subscriber3Events.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(subscriber1, subscriber2, subscriber3)

            val event = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            runBlocking {
                eventBus.publish(event)
                delay(300)
            }

            subscriber1Events.size shouldBe 1
            subscriber3Events.size shouldBe 1
        }
    }

    context("Wildcard Subscription") {
        test("should deliver all events to KodexEvent subscribers") {
            val allEvents = mutableListOf<KodexEvent>()

            val wildcardSubscriber = object : EventSubscriber<KodexEvent> {
                override val eventType: KClass<out KodexEvent> = KodexEvent::class
                override suspend fun onEvent(event: KodexEvent) {
                    allEvents.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(wildcardSubscriber)

            val userEvent = TestUserEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                userId = UUID.randomUUID(),
                action = "CREATE"
            )

            val securityEvent = TestSecurityEvent(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
                realmId = "test-realm",
                severity = EventSeverity.CRITICAL
            )

            runBlocking {
                eventBus.publish(userEvent)
                eventBus.publish(securityEvent)
                delay(300)
            }

            allEvents.size shouldBe 2
            allEvents shouldContainExactlyInAnyOrder listOf(userEvent, securityEvent)
        }

        test("should handle publishing KodexEvent base class directly") {
            val allEvents = mutableListOf<KodexEvent>()

            val wildcardSubscriber = object : EventSubscriber<KodexEvent> {
                override val eventType: KClass<out KodexEvent> = KodexEvent::class
                override suspend fun onEvent(event: KodexEvent) {
                    allEvents.add(event)
                }
            }

            val eventBus = createEventBusWithSubscribers(wildcardSubscriber)

            val baseEvent = object : KodexEvent {
                override val eventId = UUID.randomUUID()
                override val timestamp = Clock.System.now()
                override val realmId = "test-realm"
                override val eventType = "BASE_EVENT"
            }

            runBlocking {
                eventBus.publish(baseEvent)
                delay(300)
            }

            allEvents.size shouldBe 1
            allEvents[0] shouldBe baseEvent
        }
    }

    context("Shutdown") {
        test("should shutdown event bus gracefully") {
            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {}
            }

            val eventBus = createEventBusWithSubscribers(subscriber)
            eventBus.shutdown()
        }
    }

    context("Security Validation") {
        test("should reject subscribers not from registered extensions") {
            val rogueSubscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {}
            }

            val eventBus = DefaultEventBus(ExtensionRegistry.empty())

            val exception = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                eventBus.subscribe(rogueSubscriber)
            }

            exception.message shouldBe "Subscriber ${rogueSubscriber::class.qualifiedName} is not from a registered extension. " +
                "Only subscribers provided by registered extensions can subscribe to events."
        }

        test("should accept subscribers from registered extensions") {
            val receivedEvents = mutableListOf<TestUserEvent>()

            val subscriber = object : EventSubscriber<TestUserEvent> {
                override val eventType: KClass<out TestUserEvent> = TestUserEvent::class
                override suspend fun onEvent(event: TestUserEvent) {
                    receivedEvents.add(event)
                }
            }

            val provider = object : com.mustafadakhel.kodex.extension.EventSubscriberProvider {
                override fun getEventSubscribers(): List<EventSubscriber<*>> = listOf(subscriber)
            }

            val registry = com.mustafadakhel.kodex.extension.ExtensionRegistry.from(
                mapOf(com.mustafadakhel.kodex.extension.EventSubscriberProvider::class to provider)
            )

            val eventBus = DefaultEventBus(registry)

            // Should not throw - subscriber is from registered extension
            io.kotest.assertions.throwables.shouldNotThrow<IllegalArgumentException> {
                eventBus.subscribe(subscriber)
            }
        }
    }
})
