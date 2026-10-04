package ai.opennomi.app
import ai.opennomi.app.screen.FragmentOrganizer
import org.junit.Assert.*
import org.junit.Test
class FragmentOrganizerRegressionTest {
    @Test fun realStructuredSummaryRetainsItsTitleAndDeduplicatesTags() {
        val result=FragmentOrganizer.parse("```json\n{\"title\":\"申请资料\",\"category\":\"生活\",\"summary\":\"需要准备两份材料\",\"tags\":[\"#申请\",\"申请\",\"住宿\"]}\n```")
        assertEquals("申请资料",result.title);assertEquals("生活",result.category)
        assertEquals("#申请 #住宿",result.tags);assertEquals("需要准备两份材料",result.summary)
    }
    @Test(expected=IllegalArgumentException::class) fun incompleteSummaryCannotBeSavedAsSuccess() {
        FragmentOrganizer.parse("{\"title\":\"\",\"summary\":\"\"}")
    }
}
