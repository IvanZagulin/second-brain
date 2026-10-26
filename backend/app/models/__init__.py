"""Importing this package registers every table on Base.metadata."""

from app.models.base import Base
from app.models.core import (
    AgentProfile, AppSetting, Attachment, AuditLog, Device, Note, PairingCode, SyncEvent, User,
)
from app.models.chat import Conversation, Message
from app.models.memory import (
    ClarifyingQuestion, ImportChat, ImportJob, ImportMessage, LifeEvent, Memory, MemoryEmbedding,
    MemoryLink, MemorySource, MoodEntry, Person,
)
from app.models.finance import (
    Account, Budget, BudgetCategory, CategoryRule, Debt, DebtPayment, FinancialGoal, GoalContribution,
    Receipt, ReceiptItem, Subscription, TaxPayment, Transaction, TransactionMatch, TransactionSource,
    WishlistItem,
)
from app.models.fitness import (
    BodyMetric, Exercise, HealthRecord, Meal, PlanExercise, PlanRevision, ProgressPhoto, Workout,
    WorkoutPlan, WorkoutSet,
)
from app.models.tasks import Experiment, Habit, HabitLog, Task
from app.models.agent import (
    AgentAction, AgentEvent, AgentJob, AgentJobRun, ApiReservation, ApiUsage, Notification,
)

ALL_MODELS = [m for m in Base.registry.mappers]


def sync_models() -> dict[str, type[Base]]:
    """Tables mirrored to the phone, keyed by table name."""
    return {
        mapper.class_.__tablename__: mapper.class_
        for mapper in Base.registry.mappers
        if getattr(mapper.class_, "__sync__", False)
    }
