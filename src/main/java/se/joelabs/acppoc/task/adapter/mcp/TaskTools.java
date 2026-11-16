package se.joelabs.acppoc.task.adapter.mcp;

import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import se.joelabs.acppoc.mcp.ToolCallLog;
import se.joelabs.acppoc.task.application.TaskDto;
import se.joelabs.acppoc.task.application.TaskService;
import se.joelabs.acppoc.task.domain.TaskStatus;

/** Driving adapter: översätter MCP-anrop till application-tjänsten. */
@Component
class TaskTools {

    private final TaskService tasks;
    private final ToolCallLog callLog;

    TaskTools(TaskService tasks, ToolCallLog callLog) {
        this.tasks = tasks;
        this.callLog = callLog;
    }

    @McpTool(
            name = "list_tasks",
            description = "Listar uppgifterna i användarens uppgiftslista med id, titel, status och skapandetid. "
                    + "Ange status OPEN eller DONE för att bara se öppna respektive slutförda uppgifter.")
    List<TaskDto> listTasks(
            @McpToolParam(description = "Valfritt filter: OPEN eller DONE", required = false) TaskStatus status) {
        return callLog.timed("list_tasks", () -> tasks.list(status));
    }

    @McpTool(
            name = "create_task",
            description = "Skapar en ny uppgift i användarens uppgiftslista och returnerar den.")
    TaskDto createTask(@McpToolParam(description = "Kort rubrik, 1–200 tecken") String title) {
        return callLog.timed("create_task", () -> tasks.create(title));
    }

    @McpTool(
            name = "complete_task",
            description = "Slutför en uppgift i användarens uppgiftslista och returnerar den uppdaterade uppgiften. "
                    + "Att slutföra en redan slutförd uppgift är ofarligt. Ger fel om id saknas.")
    TaskDto completeTask(@McpToolParam(description = "Uppgiftens id") long id) {
        return callLog.timed("complete_task", () -> tasks.complete(id));
    }

    @McpTool(
            name = "delete_task",
            description = "Tar bort en uppgift permanent ur användarens uppgiftslista. Användaren får först godkänna. "
                    + "Ger fel om id saknas.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    String deleteTask(@McpToolParam(description = "Uppgiftens id") long id) {
        return callLog.timed("delete_task", () -> {
            var deleted = tasks.delete(id);
            return "Uppgift " + deleted.id() + " (\"" + deleted.title() + "\") togs bort.";
        });
    }
}
