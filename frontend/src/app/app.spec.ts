import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let page: HTMLElement;
  let http: HttpTestingController;

  const stats = {
    shortCode: 'abc123',
    originalUrl: 'https://example.com',
    clickCount: 2,
    createdAt: '2026-09-30T12:00:00Z',
    expiresAt: null,
    active: true,
    createdBy: '10.0.0.1',
  };
  const analytics = {
    shortCode: 'abc123',
    originalUrl: 'https://example.com',
    totalClicks: 2,
    createdAt: '2026-09-30T12:00:00Z',
    expiresAt: null,
    recentClicks: [],
    clicksByReferer: { 'https://a.example': 1, 'https://b.example': 3 },
    clicksByHour: { '09:00': 2 },
    clicksByDay: { '2026-09-30': 2 },
  };

  const forms = () => page.querySelectorAll('form');
  const text = () => page.textContent ?? '';

  function type(selector: string, value: string, form = forms()[0]) {
    const input = form.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
  }

  function submit(form: HTMLFormElement) {
    form.dispatchEvent(new Event('submit'));
  }

  async function render() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function lookUp(code: string) {
    type('input', code, forms()[1]);
    submit(forms()[1]);
    http.expectOne(`/api/stats/${code}`).flush(stats);
    http.expectOne(`/api/analytics/${code}`).flush(analytics);
    await render();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(App);
    page = fixture.nativeElement;
    http = TestBed.inject(HttpTestingController);
    await render();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('should render title', () => {
    expect(page.querySelector('h1')?.textContent).toContain('URL shortening');
  });

  it('should show field errors instead of sending an invalid form', async () => {
    type('input[formControlName="originalUrl"]', 'github.com');
    type('input[formControlName="customAlias"]', 'a/b');
    submit(forms()[0]);
    await render();

    http.expectNone('/api/shorten');
    expect(text()).toContain('The URL must start with http:// or https://.');
    expect(text()).toContain("Use 3 to 30 letters, digits, '-' or '_'.");
  });

  it('should reject an expiry in the past', async () => {
    type('input[formControlName="originalUrl"]', 'https://example.com');
    type('input[formControlName="expiresAt"]', '2020-01-01T10:00');
    submit(forms()[0]);
    await render();

    http.expectNone('/api/shorten');
    expect(text()).toContain('Pick a date and time in the future.');
  });

  it('should send expiresAt as a UTC instant', () => {
    type('input[formControlName="originalUrl"]', 'https://example.com');
    type('input[formControlName="expiresAt"]', '2099-01-01T10:00');
    submit(forms()[0]);

    const request = http.expectOne('/api/shorten');
    expect(request.request.body.expiresAt).toBe(new Date('2099-01-01T10:00').toISOString());
    expect(request.request.body.customAlias).toBeNull();
    request.flush({ shortUrl: 'http://x/api/abc123', shortCode: 'abc123', originalUrl: 'https://example.com', createdAt: '2026-09-30T12:00:00Z', expiresAt: null });
  });

  it('should clear the previous lookup when a new link is created', async () => {
    await lookUp('abc123');
    expect(page.querySelector('.badge')).not.toBeNull();

    type('input[formControlName="originalUrl"]', 'https://example.com');
    submit(forms()[0]);
    http.expectOne('/api/shorten').flush({ shortUrl: 'http://x/api/new123', shortCode: 'new123', originalUrl: 'https://example.com', createdAt: '2026-09-30T12:00:00Z', expiresAt: null });
    await render();

    expect((forms()[1].querySelector('input') as HTMLInputElement).value).toBe('new123');
    expect(page.querySelector('.badge')).toBeNull();
  });

  it('should put server field errors under the matching input', async () => {
    type('input[formControlName="originalUrl"]', 'https://example.com');
    type('input[formControlName="customAlias"]', 'taken-alias');
    submit(forms()[0]);

    http.expectOne('/api/shorten').flush(
      { title: 'Invalid request', status: 400, errors: [{ field: 'customAlias', message: 'is reserved' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    await render();

    const field = forms()[0].querySelector('input[formControlName="customAlias"]')?.parentElement;
    expect(field?.textContent).toContain('is reserved');
  });

  it('should show the ProblemDetail detail for a conflict', async () => {
    type('input[formControlName="originalUrl"]', 'https://example.com');
    type('input[formControlName="customAlias"]', 'taken');
    submit(forms()[0]);

    http.expectOne('/api/shorten').flush(
      { title: 'Alias already exists', status: 409, detail: "Custom alias 'taken' already exists" },
      { status: 409, statusText: 'Conflict' },
    );
    await render();

    expect(page.querySelector('.error')?.textContent).toContain("Custom alias 'taken' already exists");
  });

  it('should disable creating links and count down after a 429', async () => {
    vi.useFakeTimers();
    type('input[formControlName="originalUrl"]', 'https://example.com');
    submit(forms()[0]);

    http.expectOne('/api/shorten').flush(
      { title: 'Rate limit exceeded', status: 429, remainingRequests: 0, timeUntilReset: 3 },
      { status: 429, statusText: 'Too Many Requests' },
    );
    fixture.detectChanges();
    const button = forms()[0].querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Try again in 3s');

    vi.advanceTimersByTime(3000);
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
  });

  it('should URL-encode the short code when looking it up', () => {
    type('input', 'a/b', forms()[1]);
    submit(forms()[1]);

    http.expectOne('/api/stats/a%2Fb').flush({}, { status: 404, statusText: 'Not Found' });
    http.expectOne('/api/analytics/a%2Fb');
  });

  it('should show stats and analytics for a link', async () => {
    await lookUp('abc123');

    expect(text()).toContain('Active');
    expect(text()).toContain('2026-09-30');
    expect(text()).toContain('09:00');
    const referers = Array.from(page.querySelectorAll('.analytics li')).map((li) => li.textContent);
    expect(referers.findIndex((r) => r?.includes('b.example'))).toBeLessThan(
      referers.findIndex((r) => r?.includes('a.example')),
    );
  });

  it('should show the ProblemDetail detail when the link does not exist', async () => {
    type('input', 'nope', forms()[1]);
    submit(forms()[1]);

    http.expectOne('/api/stats/nope').flush(
      { title: 'Short code not found', status: 404, detail: "Short code 'nope' not found" },
      { status: 404, statusText: 'Not Found' },
    );
    http.expectOne('/api/analytics/nope');
    await render();

    expect(page.querySelector('.error')?.textContent).toContain("Short code 'nope' not found");
  });

  it('should ask for confirmation before deleting and then reload the link', async () => {
    await lookUp('abc123');
    const deleteButton = () => page.querySelector('button.danger') as HTMLButtonElement;

    deleteButton().click();
    await render();
    http.expectNone({ method: 'DELETE' });
    expect(deleteButton().textContent).toContain('Click again to confirm');

    deleteButton().click();
    http.expectOne({ method: 'DELETE', url: '/api/abc123' }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/stats/abc123').flush({ ...stats, active: false });
    http.expectOne('/api/analytics/abc123').flush(analytics);
    await render();

    expect(text()).toContain('Inactive');
    expect(page.querySelector('button.danger')).toBeNull();
  });
});
