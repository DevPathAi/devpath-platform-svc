package ai.devpath.platform.mentor;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mentor-access/invite-rounds")
public class MentorInviteRoundController {
  private final MentorInviteBatchClaimRepository batches;

  public MentorInviteRoundController(MentorInviteBatchClaimRepository batches) {
    this.batches = batches;
  }

  @GetMapping
  public List<MentorInviteBatchClaimRepository.InviteRound> latest() {
    return batches.latestCompletedRounds(12);
  }
}
