import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe, KeyValuePipe } from '@angular/common';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  ShortenResponse,
  TinylinkApi,
  UrlAnalytics,
  UrlStats,
  problemOf,
} from './tinylink-api';

/** Same rule as the backend's @Pattern on customAlias. Empty is allowed and means "generate one". */
const ALIAS_PATTERN = /^[a-zA-Z0-9_-]{3,30}$/;

function inTheFuture(control: AbstractControl): ValidationErrors | null {
  if (!control.value) {
    return null;
  }
  return new Date(control.value).getTime() > Date.now() ? null : { past: true };
}

@Component({
  selector: 'app-root',
  imports: [ReactiveFormsModule, DatePipe, KeyValuePipe],
  templateUrl: './app.html',
})
export class App {
  private readonly api = inject(TinylinkApi);
  private readonly formBuilder = inject(FormBuilder);

  protected readonly form = this.formBuilder.nonNullable.group({
    originalUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/i)]],
    customAlias: ['', Validators.pattern(ALIAS_PATTERN)],
    expiresAt: ['', inTheFuture],
  });

  protected readonly lookupForm = this.formBuilder.nonNullable.group({
    shortCode: ['', Validators.required],
  });

  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly result = signal<ShortenResponse | null>(null);
  protected readonly copied = signal(false);

  /** Seconds left before the rate limit lets this client create another link. */
  protected readonly cooldown = signal(0);
  private cooldownTimer: ReturnType<typeof setInterval> | undefined;

  protected readonly lookupLoading = signal(false);
  protected readonly lookupError = signal('');
  protected readonly stats = signal<UrlStats | null>(null);
  protected readonly analytics = signal<UrlAnalytics | null>(null);
  protected readonly confirmingDelete = signal(false);
  protected readonly deleting = signal(false);

  protected readonly topReferers = computed(() =>
    Object.entries(this.analytics()?.clicksByReferer ?? {})
      .sort(([, a], [, b]) => b - a)
      .slice(0, 5),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => clearInterval(this.cooldownTimer));
  }

  protected shortenUrl(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const payload = {
      originalUrl: raw.originalUrl.trim(),
      customAlias: raw.customAlias.trim() || null,
      // datetime-local has no zone; new Date() reads it as the browser's local time, and
      // toISOString() sends that instant in UTC, which is what the backend expects.
      expiresAt: raw.expiresAt ? new Date(raw.expiresAt).toISOString() : null,
    };

    this.loading.set(true);
    this.error.set('');
    this.result.set(null);
    this.copied.set(false);

    this.api.shorten(payload).subscribe({
      next: (response) => {
        this.result.set(response);
        // The lookup field now names the new link, so results for a previous one would be misleading.
        this.lookupForm.setValue({ shortCode: response.shortCode });
        this.stats.set(null);
        this.analytics.set(null);
        this.lookupError.set('');
        this.form.reset();
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        const problem = problemOf(error);
        if (problem?.status === 429 && problem.timeUntilReset) {
          this.startCooldown(problem.timeUntilReset);
          return;
        }
        this.error.set(this.showProblem(this.form, problem, 'Failed to create short URL.'));
      },
    });
  }

  protected lookUp(): void {
    if (this.lookupForm.invalid) {
      this.lookupForm.markAllAsTouched();
      return;
    }
    this.load(this.lookupForm.getRawValue().shortCode.trim());
  }

  protected deleteLink(): void {
    const stats = this.stats();
    if (!stats) {
      return;
    }
    if (!this.confirmingDelete()) {
      this.confirmingDelete.set(true);
      return;
    }

    this.deleting.set(true);
    this.api.delete(stats.shortCode).subscribe({
      next: () => {
        this.deleting.set(false);
        this.load(stats.shortCode);
      },
      error: (error) => {
        this.deleting.set(false);
        this.confirmingDelete.set(false);
        this.lookupError.set(problemOf(error)?.detail ?? 'Failed to delete the link.');
      },
    });
  }

  protected async copyShortUrl(shortUrl: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(shortUrl);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    } catch {
      // Clipboard needs a secure context (https or localhost); the link stays selectable.
    }
  }

  protected fieldError(form: FormGroup, name: string): string | null {
    const control = form.get(name);
    if (!control?.errors || !control.touched) {
      return null;
    }
    const errors = control.errors;
    if (errors['server']) return errors['server'];
    if (errors['required']) return 'This field is required.';
    if (errors['past']) return 'Pick a date and time in the future.';
    if (errors['pattern']) {
      return name === 'customAlias'
        ? "Use 3 to 30 letters, digits, '-' or '_'."
        : 'The URL must start with http:// or https://.';
    }
    return 'Invalid value.';
  }

  private load(shortCode: string): void {
    this.lookupLoading.set(true);
    this.lookupError.set('');
    this.confirmingDelete.set(false);
    this.stats.set(null);
    this.analytics.set(null);

    forkJoin({ stats: this.api.stats(shortCode), analytics: this.api.analytics(shortCode) }).subscribe({
      next: ({ stats, analytics }) => {
        this.stats.set(stats);
        this.analytics.set(analytics);
        this.lookupLoading.set(false);
      },
      error: (error) => {
        this.lookupError.set(problemOf(error)?.detail ?? 'Failed to fetch the link.');
        this.lookupLoading.set(false);
      },
    });
  }

  /**
   * Puts field errors from a 400 on the matching controls and returns the message for
   * everything else.
   */
  private showProblem(form: FormGroup, problem: ReturnType<typeof problemOf>, fallback: string): string {
    const unmatched: string[] = [];
    for (const violation of problem?.errors ?? []) {
      const control = form.get(violation.field);
      if (control) {
        control.setErrors({ server: violation.message });
        control.markAsTouched();
      } else {
        unmatched.push(`${violation.field} ${violation.message}`);
      }
    }
    if (problem?.errors?.length) {
      return unmatched.join(' ');
    }
    return problem?.detail ?? fallback;
  }

  private startCooldown(seconds: number): void {
    clearInterval(this.cooldownTimer);
    this.cooldown.set(seconds);
    this.cooldownTimer = setInterval(() => {
      this.cooldown.update((left) => left - 1);
      if (this.cooldown() <= 0) {
        clearInterval(this.cooldownTimer);
      }
    }, 1000);
  }
}
