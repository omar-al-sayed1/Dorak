package app.clinicreminders.balance;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface DayCloseRepository extends Repository<DayClose, Long> {

    Optional<DayClose> findByClinicIdAndDay(Long clinicId, LocalDate day);

    DayClose save(DayClose close);
}
