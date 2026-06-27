/**
 * cinema-seats.js
 *
 * Multi-seat selection on the seat-map page.
 * Click an available seat to toggle its selection.
 * The booking summary updates live with a count and total price.
 * On submit, one hidden input[name="seatNumbers"] per selected seat is injected.
 *
 * Also runs a countdown timer: once the page loads, the user has HOLD_MINUTES
 * (injected by Thymeleaf) to submit the form before it is locked.  This mirrors
 * the server-side cron that cancels PENDING bookings after the same window.
 */
document.addEventListener('DOMContentLoaded', () => {
    const grid       = document.getElementById('seat-grid-container');
    const inputsDiv  = document.getElementById('seat-inputs');
    const label      = document.getElementById('selected-label');
    const totalEl    = document.getElementById('total-price');
    const countNote  = document.getElementById('seat-count-note');
    const summary    = document.getElementById('booking-summary');
    const submitBtn  = document.getElementById('submit-btn');
    const form       = document.getElementById('booking-form');

    if (!grid) return;

    // SEAT_PRICE and HOLD_MINUTES are injected by the Thymeleaf template.
    const pricePerSeat = typeof SEAT_PRICE   !== 'undefined' ? parseFloat(SEAT_PRICE) : 0;
    const holdMinutes  = typeof HOLD_MINUTES !== 'undefined' ? parseInt(HOLD_MINUTES, 10) : 15;

    const selected = new Set();
    let sessionExpired = false;

    // ── Countdown timer ───────────────────────────────────────────────────────

    const timerEl     = document.getElementById('seat-hold-timer');
    const countdownEl = document.getElementById('hold-countdown');

    if (timerEl && countdownEl) {
        const deadlineMs = Date.now() + holdMinutes * 60 * 1000;
        timerEl.style.display = 'block';

        const interval = setInterval(() => {
            const remaining = deadlineMs - Date.now();

            if (remaining <= 0) {
                clearInterval(interval);
                sessionExpired = true;
                countdownEl.textContent = '00:00';
                timerEl.classList.add('seat-hold-timer--expired');
                lockExpiredSession();
                return;
            }

            const mins = Math.floor(remaining / 60000);
            const secs = Math.floor((remaining % 60000) / 1000);
            countdownEl.textContent =
                String(mins).padStart(2, '0') + ':' + String(secs).padStart(2, '0');

            // Warn when less than 2 minutes remain.
            if (remaining < 120_000) {
                timerEl.classList.add('seat-hold-timer--warning');
            }
        }, 1000);
    }

    function lockExpiredSession() {
        // Disable all seat buttons.
        grid.querySelectorAll('button.seat').forEach(btn => { btn.disabled = true; });
        // Disable the submit button and show a message.
        if (submitBtn) submitBtn.disabled = true;
        if (timerEl) {
            timerEl.innerHTML = '⚠ Session expired. Please <a href="">refresh the page</a> to start over.';
        }
    }

    // ── Seat selection ────────────────────────────────────────────────────────

    grid.addEventListener('click', (event) => {
        if (sessionExpired) return;
        const btn = event.target.closest('button.seat');
        if (!btn || btn.disabled) return;

        const seat = btn.dataset.seat;

        if (selected.has(seat)) {
            selected.delete(seat);
            btn.classList.replace('seat-selected', 'seat-available');
        } else {
            selected.add(seat);
            btn.classList.replace('seat-available', 'seat-selected');
        }

        updateSummary();
    });

    function updateSummary() {
        const count = selected.size;
        const total = (count * pricePerSeat).toFixed(2);

        if (count === 0) {
            label.textContent = '—';
            totalEl.textContent = '0';
            countNote.textContent = '';
            summary.classList.remove('visible');
            submitBtn.disabled = true;
        } else {
            const sorted = Array.from(selected).sort();
            label.textContent = sorted.join(', ');
            totalEl.textContent = total;
            countNote.textContent = count === 1 ? '(1 seat)' : `(${count} seats × SAR ${pricePerSeat.toFixed(2)})`;
            summary.classList.add('visible');
            submitBtn.disabled = sessionExpired;
        }
    }

    // Before the form submits, inject one hidden input per selected seat.
    form.addEventListener('submit', (event) => {
        if (sessionExpired) {
            event.preventDefault();
            return;
        }

        // Clear any previously injected inputs.
        inputsDiv.innerHTML = '';

        if (selected.size === 0) {
            event.preventDefault();
            return;
        }

        for (const seat of selected) {
            const input = document.createElement('input');
            input.type  = 'hidden';
            input.name  = 'seatNumbers';
            input.value = seat;
            inputsDiv.appendChild(input);
        }
    });
});
