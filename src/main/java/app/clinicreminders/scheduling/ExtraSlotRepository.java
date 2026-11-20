package app.clinicreminders.scheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface ExtraSlotRepository extends Repository<ExtraSlot, Long> {

    /** The doctor's extra slots from {@code from} to {@code to}, both inclusive. */
    List<ExtraSlot> findByClinicIdAndDoctorIdAndDateBetween(Long clinicId, Long doctorId, LocalDate from, LocalDate to);

    List<ExtraSlot> findByClinicIdAndDoctorId(Long clinicId, Long doctorId);

    Optional<ExtraSlot> findByIdAndClinicId(Long id, Long clinicId);

    ExtraSlot save(ExtraSlot slot);

    void delete(ExtraSlot slot);
}
