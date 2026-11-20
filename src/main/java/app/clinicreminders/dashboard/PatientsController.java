package app.clinicreminders.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Patients moved to the React dashboard (the table at /app/patients, with search, filters, sorting
 * and deleting on request through {@code PatientsApi}). The old address, and its search, still work:
 * bookmarks and the classic dashboard's links land on the table.
 */
@Controller
class PatientsController {

    @GetMapping("/patients")
    String patients(@RequestParam(defaultValue = "") String q) {
        UriComponentsBuilder table = UriComponentsBuilder.fromPath("/app/patients");
        if (!q.isBlank()) {
            table.queryParam("q", q.strip());
        }
        return "redirect:" + table.encode().build().toUriString();
    }
}
