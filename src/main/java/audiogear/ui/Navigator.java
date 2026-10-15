package audiogear.ui;

import java.util.List;

/** Moves between screens, carrying a selection along. */
public interface Navigator {
    void catalog(Long selectId);
    void compare(List<Long> gearIds);
    void curves(List<Long> gearIds);
    void pairing(Long transducerId, Long ampId);
    void discover(Long gearId);
    void wishlist(Long selectId);
}
