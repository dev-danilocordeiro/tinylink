import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    })
      .compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('URL shortening');
  });

  it('should show the ProblemDetail detail when fetching stats fails', async () => {
    const fixture = TestBed.createComponent(App);
    const compiled = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    await fixture.whenStable();

    const input = compiled.querySelectorAll('form')[1].querySelector('input') as HTMLInputElement;
    input.value = 'nope';
    input.dispatchEvent(new Event('input'));
    compiled.querySelectorAll('form')[1].dispatchEvent(new Event('submit'));

    http.expectOne('/api/stats/nope').flush(
      { title: 'Short code not found', status: 404, detail: "Short code 'nope' not found" },
      { status: 404, statusText: 'Not Found' },
    );
    await fixture.whenStable();

    expect(compiled.querySelector('.error')?.textContent).toContain("Short code 'nope' not found");
    http.verify();
  });

  it('should list validation messages from a 400 ProblemDetail', async () => {
    const fixture = TestBed.createComponent(App);
    const compiled = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    await fixture.whenStable();

    const input = compiled.querySelector('input[formControlName="originalUrl"]') as HTMLInputElement;
    input.value = 'https://example.com';
    input.dispatchEvent(new Event('input'));
    compiled.querySelector('form')?.dispatchEvent(new Event('submit'));

    http.expectOne('/api/shorten').flush(
      {
        title: 'Invalid request',
        status: 400,
        detail: 'Request validation failed',
        errors: [{ field: 'customAlias', message: 'Custom alias must be 3 to 30 characters' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    expect(compiled.querySelector('.error')?.textContent).toContain('Custom alias must be 3 to 30 characters');
    http.verify();
  });
});
