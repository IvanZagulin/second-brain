package ru.secondbrain.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import ru.secondbrain.app.core.parseDate
import ru.secondbrain.app.core.parseInstant

/** Server table names of mirrored entities. */
object E {
    const val ACCOUNTS = "accounts"
    const val CATEGORIES = "budget_categories"
    const val TRANSACTIONS = "transactions"
    const val SUBSCRIPTIONS = "subscriptions"
    const val DEBTS = "debts"
    const val DEBT_PAYMENTS = "debt_payments"
    const val GOALS = "financial_goals"
    const val GOAL_CONTRIBUTIONS = "goal_contributions"
    const val TAXES = "tax_payments"
    const val BUDGETS = "budgets"
    const val WISHLIST = "wishlist_items"
    const val RECEIPTS = "receipts"
    const val EXERCISES = "exercises"
    const val PLANS = "workout_plans"
    const val PLAN_EXERCISES = "plan_exercises"
    const val WORKOUTS = "workouts"
    const val SETS = "workout_sets"
    const val METRICS = "body_metrics"
    const val PHOTOS = "progress_photos"
    const val MEALS = "meals"
    const val TASKS = "tasks"
    const val HABITS = "habits"
    const val HABIT_LOGS = "habit_logs"
    const val EXPERIMENTS = "experiments"
    const val MOOD = "mood_entries"
    const val NOTES = "notes"
    const val MEMORIES = "memories"
    const val PEOPLE = "people"
    const val EVENTS = "life_events"
    const val QUESTIONS = "clarifying_questions"
    const val CONVERSATIONS = "conversations"
    const val MESSAGES = "messages"
    const val NOTIFICATIONS = "notifications"
    const val ACTIONS = "agent_actions"
    const val JOBS = "agent_jobs"
}

@Serializable
data class Account(
    val id: String = "", val name: String = "", val kind: String = "card", val bank: String? = null,
    val currency: String = "RUB", val cardLast4: List<String> = emptyList(), val isBusiness: Boolean = false,
    val includeInFree: Boolean = true, val balanceMinor: Long = 0, val balanceAt: String? = null,
    val balanceSource: String? = null, val archived: Boolean = false, val sort: Int = 0,
)

@Serializable
data class BudgetCategory(
    val id: String = "", val name: String = "", val code: String? = null, val kind: String = "expense",
    val icon: String? = null, val color: String? = null, val isSystem: Boolean = false, val isImpulse: Boolean = false,
    val monthlyLimitMinor: Long? = null, val sort: Int = 0,
)

@Serializable
data class Transaction(
    val id: String = "", val accountId: String? = null, val occurredAt: String? = null, val hasTime: Boolean = true,
    val amountMinor: Long = 0, val currency: String = "RUB", val direction: String = "out",
    val flowType: String = "expense", val scope: String = "personal", val categoryId: String? = null,
    val categoryConfidence: Double? = null, val categorySource: String? = null, val merchant: String? = null,
    val description: String? = null, val cardLast4: String? = null, val status: String = "new",
    val sourceRank: Int = 1, val note: String? = null, val origin: String = "manual", val receiptId: String? = null,
) {
    val at get() = parseInstant(occurredAt)
    val signed get() = if (direction == "in") amountMinor else -amountMinor
    val title get() = merchant ?: description ?: if (direction == "in") "Поступление" else "Расход"
}

@Serializable
data class Subscription(
    val id: String = "", val name: String = "", val amountMinor: Long = 0, val currency: String = "RUB",
    val period: String = "monthly", val periodDays: Int? = null, val nextChargeDate: String? = null,
    val accountId: String? = null, val categoryId: String? = null, val isActive: Boolean = true,
    val usefulness: Int? = null, val notes: String? = null, val origin: String = "manual", val remindDaysBefore: Int = 2,
) {
    val next get() = parseDate(nextChargeDate)
    val monthlyMinor: Long get() = when (period) {
        "weekly" -> amountMinor * 52 / 12
        "quarterly" -> amountMinor / 3
        "yearly" -> amountMinor / 12
        "days" -> if ((periodDays ?: 0) > 0) amountMinor * 30 / periodDays!! else amountMinor
        else -> amountMinor
    }
}

@Serializable
data class Debt(
    val id: String = "", val creditor: String = "", val kind: String = "microloan", val principalMinor: Long = 0,
    val balanceMinor: Long = 0, val ratePercent: Double? = null, val ratePeriod: String = "year",
    val nextPaymentDate: String? = null, val nextPaymentMinor: Long? = null, val paymentPeriod: String? = null,
    val holidayUntil: String? = null, val holidayTerms: String? = null, val termsConfirmed: Boolean = false,
    val status: String = "active", val earlyRepaymentAllowed: Boolean? = null, val priority: Int = 2,
    val notes: String? = null,
)

@Serializable
data class DebtPayment(val id: String = "", val debtId: String = "", val paidAt: String? = null,
                       val amountMinor: Long = 0, val kind: String = "scheduled", val note: String? = null)

@Serializable
data class FinancialGoal(
    val id: String = "", val name: String = "", val targetMinor: Long = 0, val targetMaxMinor: Long? = null,
    val savedMinor: Long = 0, val deadline: String? = null, val priority: Int = 2, val monthlyPlanMinor: Long? = null,
    val status: String = "active", val notes: String? = null,
) {
    val progress: Float get() = if (targetMinor > 0) (savedMinor.toFloat() / targetMinor).coerceIn(0f, 1f) else 0f
}

@Serializable
data class GoalContribution(val id: String = "", val goalId: String = "", val at: String? = null,
                            val amountMinor: Long = 0, val note: String? = null)

@Serializable
data class TaxPayment(
    val id: String = "", val kind: String = "", val period: String = "", val dueDate: String? = null,
    val estimatedMinor: Long = 0, val paidMinor: Long = 0, val paidAt: String? = null, val status: String = "planned",
    val notes: String? = null,
)

@Serializable
data class Reserve(val name: String = "", val amountMinor: Long = 0)

@Serializable
data class Budget(val id: String = "", val month: String? = null, val totalLimitMinor: Long? = null,
                  val reserves: List<Reserve> = emptyList(), val savingsModeUntil: String? = null, val notes: String? = null)

@Serializable
data class WishlistItem(
    val id: String = "", val title: String = "", val priceMinor: Long? = null, val url: String? = null,
    val note: String? = null, val analysis: JsonObject = JsonObject(emptyMap()), val verdict: String? = null,
    val waitUntil: String? = null, val decision: String? = null, val createdAt: String? = null,
)

@Serializable
data class Receipt(val id: String = "", val transactionId: String? = null, val store: String? = null,
                   val occurredAt: String? = null, val totalMinor: Long? = null, val status: String = "parsed",
                   val origin: String = "qr")

@Serializable
data class Exercise(val id: String = "", val name: String = "", val muscleGroup: String = "other",
                    val equipment: String? = null, val isBodyweight: Boolean = false, val notes: String? = null,
                    val archived: Boolean = false)

@Serializable
data class WorkoutPlan(val id: String = "", val name: String = "", val weekday: Int? = null,
                       val muscleGroups: List<String> = emptyList(), val plannedMinutes: Int? = null,
                       val notes: String? = null, val isActive: Boolean = true, val sort: Int = 0)

@Serializable
data class PlanExercise(val id: String = "", val planId: String = "", val exerciseId: String = "", val position: Int = 0,
                        val targetSets: Int? = null, val targetReps: String? = null, val targetWeight: Double? = null,
                        val restSeconds: Int? = null, val notes: String? = null)

@Serializable
data class Workout(
    val id: String = "", val planId: String? = null, val startedAt: String? = null, val endedAt: String? = null,
    val status: String = "in_progress", val feeling: Int? = null, val painNote: String? = null,
    val notes: String? = null, val skipReason: String? = null, val analysis: String? = null,
) {
    val start get() = parseInstant(startedAt)
    val end get() = parseInstant(endedAt)
}

@Serializable
data class WorkoutSet(val id: String = "", val workoutId: String = "", val exerciseId: String = "", val setNo: Int = 1,
                      val reps: Int? = null, val weight: Double? = null, val rpe: Double? = null,
                      val restSeconds: Int? = null, val isWarmup: Boolean = false, val note: String? = null,
                      val doneAt: String? = null)

@Serializable
data class BodyMetric(val id: String = "", val measuredAt: String? = null, val kind: String = "weight",
                      val value: Double = 0.0, val unit: String = "kg", val note: String? = null)

@Serializable
data class ProgressPhoto(val id: String = "", val takenAt: String? = null, val pose: String? = null,
                         val localPath: String? = null, val attachmentId: String? = null, val weight: Double? = null,
                         val note: String? = null)

@Serializable
data class Meal(val id: String = "", val eatenAt: String? = null, val description: String? = null,
                val attachmentId: String? = null, val kcal: Int? = null, val proteinG: Double? = null,
                val fatG: Double? = null, val carbsG: Double? = null, val errorPct: Int? = null,
                val estimatedBy: String? = null, val note: String? = null)

@Serializable
data class Task(
    val id: String = "", val parentId: String? = null, val title: String = "", val notes: String? = null,
    val dueAt: String? = null, val hasTime: Boolean = false, val priority: Int = 1, val status: String = "open",
    val repeatRule: String? = null, val remindAt: String? = null, val completedAt: String? = null,
    val postponedCount: Int = 0, val commitment: String = "firm", val source: String = "user",
) {
    val due get() = parseInstant(dueAt)
}

@Serializable
data class Habit(val id: String = "", val name: String = "", val kind: String = "good", val schedule: String = "daily",
                 val targetValue: Double? = null, val unit: String? = null, val isActive: Boolean = true,
                 val notes: String? = null)

@Serializable
data class HabitLog(val id: String = "", val habitId: String = "", val day: String? = null, val value: Double = 1.0,
                    val note: String? = null)

@Serializable
data class Experiment(val id: String = "", val title: String = "", val hypothesis: String = "",
                      val startDate: String? = null, val endDate: String? = null, val status: String = "active",
                      val conclusion: String? = null, val metrics: JsonArray = JsonArray(emptyList()),
                      val results: JsonObject = JsonObject(emptyMap()))

@Serializable
data class MoodEntry(val id: String = "", val occurredAt: String? = null, val score: Int = 5, val energy: Int? = null,
                     val emotions: List<String> = emptyList(), val cause: String? = null, val note: String? = null)

@Serializable
data class Note(val id: String = "", val text: String = "", val tags: List<String> = emptyList(), val createdAt: String? = null)

@Serializable
data class Memory(
    val id: String = "", val type: String = "permanent", val category: String = "general", val title: String? = null,
    val content: String = "", val eventAt: String? = null, val sourceKind: String = "chat", val sourceId: String? = null,
    val confidence: Double = 0.7, val status: String = "unconfirmed", val isActual: Boolean = true,
    val sensitivity: String = "normal", val importance: Int = 3, val createdAt: String? = null,
    val evidence: JsonArray = JsonArray(emptyList()),
)

@Serializable
data class Person(val id: String = "", val name: String = "", val aliases: List<String> = emptyList(),
                  val relation: String? = null, val description: String? = null,
                  val observations: JsonArray = JsonArray(emptyList()), val unresolved: List<String> = emptyList(),
                  val importance: Int = 3, val lastMentionedAt: String? = null)

@Serializable
data class LifeEvent(val id: String = "", val title: String = "", val description: String? = null,
                     val occurredAt: String? = null, val kind: String = "event", val sentiment: Int? = null,
                     val importance: Int = 3, val people: List<String> = emptyList())

@Serializable
data class ClarifyingQuestion(val id: String = "", val question: String = "", val context: String? = null,
                              val status: String = "open", val answer: String? = null, val priority: Int = 2,
                              val createdAt: String? = null)

@Serializable
data class Conversation(val id: String = "", val title: String = "Новый диалог", val mode: String = "auto",
                        val kind: String = "chat", val summary: String? = null, val archived: Boolean = false,
                        val pinned: Boolean = false, val lastMessageAt: String? = null, val createdAt: String? = null)

@Serializable
data class Message(val id: String = "", val conversationId: String = "", val role: String = "user",
                   val text: String = "", val attachments: List<String> = emptyList(),
                   val toolEvents: JsonArray = JsonArray(emptyList()), val model: String? = null,
                   val costUsd: Double? = null, val status: String = "complete", val source: String = "app",
                   val createdAt: String? = null)

@Serializable
data class NotificationItem(val id: String = "", val title: String = "", val body: String = "",
                            val category: String = "agent", val priority: String = "normal", val silent: Boolean = false,
                            val sensitive: Boolean = false, val conversationId: String? = null,
                            val messageId: String? = null, val actionId: String? = null,
                            val data: JsonObject = JsonObject(emptyMap()), val createdAt: String? = null,
                            val readAt: String? = null)

@Serializable
data class AgentAction(val id: String = "", val tool: String = "", val level: String = "A", val title: String? = null,
                       val params: JsonObject = JsonObject(emptyMap()), val status: String = "done",
                       val result: JsonObject = JsonObject(emptyMap()), val rollback: JsonObject? = null,
                       val initiator: String = "chat", val conversationId: String? = null,
                       val createdAt: String? = null, val decidedAt: String? = null, val error: String? = null)

@Serializable
data class AgentJob(val id: String = "", val kind: String = "", val goal: String = "", val reason: String? = null,
                    val nextRunAt: String? = null, val priority: Int = 1, val status: String = "scheduled",
                    val recurrence: String? = null, val attempts: Int = 0, val lastRunAt: String? = null,
                    val createdBy: String = "system")

val MUSCLE_GROUPS = linkedMapOf(
    "back" to "Спина", "biceps" to "Бицепс", "shoulders" to "Плечи", "chest" to "Грудь", "triceps" to "Трицепс",
    "legs" to "Ноги", "core" to "Пресс", "cardio" to "Кардио", "other" to "Другое",
)

val MEMORY_TYPES = linkedMapOf(
    "permanent" to "Постоянная", "episodic" to "События", "behavioral" to "Поведение", "working" to "Рабочая",
    "analytical" to "Выводы",
)

val MEMORY_CATEGORIES = linkedMapOf(
    "biography" to "Биография", "interests" to "Интересы", "preferences" to "Предпочтения", "goals" to "Цели",
    "people" to "Люди", "health" to "Здоровье", "finance" to "Деньги", "work" to "Работа",
    "relationships" to "Отношения", "habits" to "Привычки", "fitness" to "Тренировки", "general" to "Общее",
)

val CHAT_MODES = linkedMapOf(
    "auto" to "Авто", "normal" to "Обычный", "coach" to "Жёсткий коуч", "psych" to "Психологический разбор",
    "finance" to "Финансовый аналитик", "trainer" to "Тренер", "critic" to "Критик", "companion" to "Собеседник",
)

val FLOW_LABELS = linkedMapOf(
    "expense" to "Расход", "income" to "Доход", "transfer" to "Перевод между своими", "cash_deposit" to "Внесение наличных",
    "cash_withdrawal" to "Снятие наличных", "debt_payment" to "Платёж по долгу", "tax" to "Налог", "refund" to "Возврат",
    "savings" to "Накопления", "correction" to "Корректировка",
)
