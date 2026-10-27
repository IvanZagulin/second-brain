package ru.secondbrain.app.ui.help

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Spark
import ru.secondbrain.app.ui.theme.AgentProse

private data class HelpSection(val title: String, val points: List<String>, val link: Pair<String, String>? = null)

private val SECTIONS = listOf(
    HelpSection("Что это", listOf(
        "Личный агент, который знает ваши деньги, тренировки, задачи и то, что вы ему рассказывали.",
        "Он отвечает в чате и сам пишет первым, когда есть повод: перерасход, тренировка, обещание, отчёт.",
    )),
    HelpSection("Чат", listOf(
        "Спрашивайте как человека: «сколько я могу потратить до зарплаты», «как прошла неделя», «разбери мою тренировку».",
        "Агент сам смотрит нужные данные — это строка «✓ Смотрю финансы» над ответом.",
        "Режим сверху меняет манеру: жёсткий коуч, психологический разбор, финансовый аналитик, тренер, критик, собеседник.",
        "Можно прикрепить файл или фото и надиктовать голосом. Без интернета сообщение уйдёт, когда появится связь.",
    ), "Открыть чат" to "chat"),
    HelpSection("Что агент помнит", listOf(
        "Внутри одного диалога — всё, что вы писали.",
        "Между диалогами — через три минуты после разговора он выписывает важное в «Память»: факты о вас, планы, обещания, привычки, выводы.",
        "В новом диалоге он сам находит относящиеся воспоминания, поэтому повторять не нужно.",
        "Всё видно во вкладке «Память»: можно исправить, удалить или пометить секретным. Туда же можно загрузить архив Telegram, ChatGPT, Claude или Gemini.",
    ), "Открыть память" to "memory"),
    HelpSection("Деньги", listOf(
        "Покупки по уведомлениям Mir Pay, Сбера и Т-Банка учитываются сами — нужен доступ к уведомлениям.",
        "Выписки в PDF загружаются в «Финансы → Импорт выписки»; повторы не задваиваются.",
        "«Можно потратить сегодня» считается от остатков, обязательных платежей и дня следующего поступления. Остатки уточняйте в «Финансы → Счета».",
        "«Хочу купить» — агент разберёт покупку: потянете ли и что она сдвинет.",
    ), "Открыть финансы" to "finance"),
    HelpSection("Тренировки и питание", listOf(
        "Программы на вторник и четверг уже заведены. «Начать» — режим тренировки с подходами и таймером отдыха.",
        "После тренировки тренер разбирает прогресс и нагрузку и предлагает, что поменять.",
        "Питание — по желанию: опишите блюдо или сфотографируйте, агент оценит калории и БЖУ (погрешность 20–40%). Это нужно, чтобы советы по весу (105 → 95 кг) опирались на факты.",
    ), "Открыть тренировки" to "fitness"),
    HelpSection("Когда агент пишет первым", listOf(
        "По умолчанию: утром около 7:40, вечерний чек-ин в 21:30, отчёт по воскресеньям, напоминания и реакции на траты.",
        "С 23:30 до 7:00 молчит — важное откладывает до утра и перед отправкой перепроверяет.",
        "Частоту, паузу и время меняют «Настройки → Инициативность».",
    ), "Настроить" to "settings/initiative"),
    HelpSection("Где хранятся данные", listOf(
        "Всё лежит на вашем сервере brain.quasar-x.ru и в зашифрованной базе на телефоне. Ежедневно делается зашифрованная резервная копия.",
        "В Claude уходит только то, что нужно для конкретного ответа. Секретные воспоминания — никогда.",
        "Фото (еда, чеки, вложения) сохраняются на сервере. Claude видит их, только если включено «Настройки → Приватность → Изображения»; иначе оценка идёт по вашему описанию.",
        "Расходы на Claude ограничены $100 в месяц; где сколько — «Настройки → Расходы на API».",
    ), "Приватность" to "settings/privacy"),
)

@Composable
fun HelpScreen() {
    val nav = LocalNavigate.current
    Screen("Как пользоваться") {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spark(22.dp)
                Spacer(Modifier.width(10.dp))
                Text("Коротко о том, что умеет агент", style = AgentProse)
            }
        }
        SECTIONS.forEach { s ->
            item {
                AppCard {
                    Text(s.title, style = MaterialTheme.typography.titleLarge)
                    Gap(6)
                    s.points.forEach { p ->
                        Row(Modifier) {
                            Text("•  ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p, style = MaterialTheme.typography.bodyLarge)
                        }
                        Gap(4)
                    }
                    s.link?.let { (label, route) -> TextButton(onClick = { nav(route) }) { Text(label) } }
                }
            }
        }
        item { Secondary("Вопрос, которого тут нет, можно задать самому агенту: «что ты умеешь?»") }
    }
}
