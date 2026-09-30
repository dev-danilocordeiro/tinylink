import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ShortenRequest = {
  originalUrl: string;
  customAlias: string | null;
  /** ISO-8601 instant, e.g. "2030-01-01T13:00:00.000Z". */
  expiresAt: string | null;
};

export type ShortenResponse = {
  shortUrl: string;
  shortCode: string;
  originalUrl: string;
  createdAt: string;
  expiresAt: string | null;
};

export type UrlStats = {
  shortCode: string;
  originalUrl: string;
  clickCount: number;
  createdAt: string;
  expiresAt: string | null;
  active: boolean;
  createdBy: string;
};

export type ClickEvent = {
  timestamp: string;
  ipAddress: string;
  userAgent: string | null;
  referer: string | null;
};

export type UrlAnalytics = {
  shortCode: string;
  originalUrl: string;
  totalClicks: number;
  createdAt: string;
  expiresAt: string | null;
  recentClicks: ClickEvent[];
  clicksByReferer: Record<string, number>;
  clicksByHour: Record<string, number>;
  clicksByDay: Record<string, number>;
};

export type FieldViolation = { field: string; message: string };

/** Error body returned by the backend (RFC 9457). */
export type ProblemDetail = {
  title?: string;
  status?: number;
  detail?: string;
  errors?: FieldViolation[];
  timeUntilReset?: number;
};

@Injectable({ providedIn: 'root' })
export class TinylinkApi {
  private readonly http = inject(HttpClient);

  shorten(request: ShortenRequest): Observable<ShortenResponse> {
    return this.http.post<ShortenResponse>('/api/shorten', request);
  }

  stats(shortCode: string): Observable<UrlStats> {
    return this.http.get<UrlStats>(`/api/stats/${encodeURIComponent(shortCode)}`);
  }

  analytics(shortCode: string): Observable<UrlAnalytics> {
    return this.http.get<UrlAnalytics>(`/api/analytics/${encodeURIComponent(shortCode)}`);
  }

  delete(shortCode: string): Observable<void> {
    return this.http.delete<void>(`/api/${encodeURIComponent(shortCode)}`);
  }
}

export function problemOf(error: unknown): ProblemDetail | null {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
    return error.error as ProblemDetail;
  }
  return null;
}
