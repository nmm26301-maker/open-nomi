package ai.opennomi.app.model

enum class ConversationState(val label: String) {
    IDLE("我在这儿"), LISTENING("我在认真听"), THINKING("让我想一想"), SPEAKING("我在回应你")
}
