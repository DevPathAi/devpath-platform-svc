package ai.devpath.platform.mentor;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/mentor/invite-batches")
public class AdminMentorInviteBatchController {
  private final MentorInviteBatchService service;

  public AdminMentorInviteBatchController(MentorInviteBatchService service) {
    this.service = service;
  }

  @PostMapping("/{date}/run")
  public MentorInviteBatchService.BatchRun run(@PathVariable LocalDate date) {
    return service.run(date);
  }
}
