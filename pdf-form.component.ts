import { Component } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { PdfService } from '../pdf.service';

@Component({
  selector: 'app-pdf-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <h2>Upload PDF Form</h2>
    <input type="file" (change)="onFile($event)" accept="application/pdf" />

    <section *ngIf="schema">
      <form [formGroup]="fg" (ngSubmit)="onSubmit()">
        <div *ngFor="let sec of layout" class="section">
          <h3>{{ sec.title }}</h3>
          <div *ngFor="let key of sec.keys" class="field">
            <label>{{ schema.properties[key]?.title || key }}</label>
            <ng-container [ngSwitch]="uiHints[key] || 'text'">
              <input *ngSwitchCase="'text'" type="text" [formControlName]="key" />
              <input *ngSwitchCase="'checkbox'" type="checkbox" [formControlName]="key" />
              <div *ngSwitchCase="'radio'">
                <label *ngFor="let opt of schema.properties[key].enum">
                  <input type="radio" [value]="opt" [formControlName]="key" /> {{opt}}
                </label>
              </div>
              <select *ngSwitchCase="'select'" [formControlName]="key">
                <option *ngFor="let opt of schema.properties[key].enum" [value]="opt">{{opt}}</option>
              </select>
              <select multiple *ngSwitchCase="'multiselect'" [formControlName]="key">
                <option *ngFor="let opt of schema.properties[key].items.enum" [value]="opt">{{opt}}</option>
              </select>
            </ng-container>
          </div>
        </div>
        <button type="submit">Submit</button>
      </form>

      <h4>Provenance</h4>
      <pre>{{ provenance | json }}</pre>

      <h4>Docling (raw)</h4>
      <pre>{{ docling | json }}</pre>
    </section>
  `,
  styles: [`
    .section { border: 1px solid #ddd; padding: 12px; margin: 12px 0; }
    .field { margin: 8px 0; display: flex; gap: 8px; align-items: center; }
  `]
})
export class PdfFormComponent {
  fg!: FormGroup;
  schema: any; uiHints: any; provenance: any; docling: any;
  layout: {title:string, keys:string[]}[] = [];

  constructor(private fb: FormBuilder, private api: PdfService) {}

  onFile(e: any) {
    const f: File = e.target.files?.[0];
    if (!f) return;

    this.api.upload(f).subscribe(res => {
      this.schema = res.schema;
      this.uiHints = res.uiHints || {};
      this.provenance = res.provenance || {};
      this.docling = res.docling || {};
      this.layout = (res.layout || []).map((s:any) => ({ title: s.title, keys: s.keys || [] }));

      const ctrls: any = {};
      const allKeys = Object.keys(this.schema.properties || {});
      for (const k of allKeys) ctrls[k] = [''];
      this.fg = this.fb.group(ctrlls as any);
    });
  }

  onSubmit() {
    console.log('form value', this.fg.value);
  }
}
