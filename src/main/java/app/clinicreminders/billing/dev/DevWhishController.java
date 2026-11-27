package app.clinicreminders.billing.dev;

import java.util.UUID;

import app.clinicreminders.billing.FakeWhish;
import app.clinicreminders.billing.PaymentGateway.Checkout;
import app.clinicreminders.common.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The fake Whish's payment page ({@code whish.client=fake}): what the owner would see on Whish, with
 * Pay and Cancel. Either one sends them back to Dorak, as Whish would.
 */
@Controller
@RequestMapping("/dev/whish")
@ConditionalOnProperty(name = "whish.client", havingValue = "fake")
class DevWhishController {

    private final FakeWhish whish;

    DevWhishController(FakeWhish whish) {
        this.whish = whish;
    }

    @GetMapping("/{id}")
    String page(@PathVariable UUID id, Model model) {
        model.addAttribute("checkout", checkout(id));
        return "dev/whish";
    }

    @PostMapping("/{id}/pay")
    String pay(@PathVariable UUID id) {
        Checkout checkout = checkout(id);
        whish.pay(id);
        return "redirect:" + checkout.returnUrl();
    }

    @PostMapping("/{id}/cancel")
    String cancel(@PathVariable UUID id) {
        Checkout checkout = checkout(id);
        whish.decline(id);
        return "redirect:" + checkout.returnUrl();
    }

    private Checkout checkout(UUID id) {
        return whish.checkoutOf(id).orElseThrow(() -> new NotFoundException("Fake Whish payment", id));
    }
}
