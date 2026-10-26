package ru.secondbrain.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.secondbrain.app.ui.agent.recurrenceLabel

class AgentLabelsTest {
    @Test fun recurrence() {
        assertEquals("каждые 15 мин", recurrenceLabel("every:15m"))
        assertEquals("каждые 2 ч", recurrenceLabel("every:2h"))
        assertEquals("ежедневно в 07:40", recurrenceLabel("daily@07:40"))
        assertEquals("по пн, ср в 19:00", recurrenceLabel("weekly:1,3@19:00"))
        assertEquals("ежемесячно 5-го в 10:00", recurrenceLabel("monthly:5@10:00"))
        assertEquals("что-то новое", recurrenceLabel("что-то новое"))
    }
}
