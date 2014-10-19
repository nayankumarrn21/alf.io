/**
 * This file is part of bagarino.
 *
 * bagarino is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * bagarino is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with bagarino.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.bagarino.controller;

import static io.bagarino.util.OptionalWrapper.optionally;
import io.bagarino.manager.system.MailManager;
import io.bagarino.manager.system.MailManager.Attachment;
import io.bagarino.model.Event;
import io.bagarino.model.Ticket;
import io.bagarino.model.TicketCategory;
import io.bagarino.model.TicketReservation;
import io.bagarino.model.TicketReservation.TicketReservationStatus;
import io.bagarino.repository.EventRepository;
import io.bagarino.repository.TicketCategoryRepository;
import io.bagarino.repository.TicketRepository;
import io.bagarino.repository.TicketReservationRepository;
import io.bagarino.util.DateFormatterInterceptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.Data;

import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.mustache.jmustache.LocalizationMessageInterceptor;
import org.xhtmlrenderer.pdf.ITextRenderer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.samskivert.mustache.Mustache;

@Controller
public class TicketController {

	private final EventRepository eventRepository;
	private final TicketReservationRepository ticketReservationRepository;
	private final TicketRepository ticketRepository;
	private final TicketCategoryRepository ticketCategoryRepository;
	private final MailManager mailManager;
	private final LocalizationMessageInterceptor localizationMessageInterceptor;

	@Autowired
	public TicketController(EventRepository eventRepository, TicketReservationRepository ticketReservationRepository,
			TicketRepository ticketRepository, TicketCategoryRepository ticketCategoryRepository,
			MailManager mailManager,
			LocalizationMessageInterceptor localizationMessageInterceptor) {
		this.eventRepository = eventRepository;
		this.ticketReservationRepository = ticketReservationRepository;
		this.ticketRepository = ticketRepository;
		this.ticketCategoryRepository = ticketCategoryRepository;
		this.mailManager = mailManager;
		this.localizationMessageInterceptor = localizationMessageInterceptor;
	}
	
	@RequestMapping(value = "/event/{eventName}/reservation/{reservationId}/{ticketIdentifier}", method = RequestMethod.GET)
	public String showTicket(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId, 
			@PathVariable("ticketIdentifier") String ticketIdentifier, Model model) {
		
		Triple<Event, TicketReservation, Ticket> data = fetch(eventName, reservationId, ticketIdentifier);
		check(data.getMiddle(), data.getRight());
		
		model.addAttribute("ticket", data.getRight());
		
		return "/event/show-ticket";
	}

	@RequestMapping(value = "/event/{eventName}/reservation/{reservationId}/{ticketIdentifier}", method = RequestMethod.POST)
	public String assignTicketToPerson(@PathVariable("eventName") String eventName,
			@PathVariable("reservationId") String reservationId,
			@PathVariable("ticketIdentifier") String ticketIdentifier,
			UpdateTicketOwnerForm updateTicketOwner, BindingResult bindingResult, HttpServletRequest request, HttpServletResponse response) throws Exception {
		
		//TODO: validate email, fullname (not null, maxlength 255)
		ticketRepository.updateTicketOwner(ticketIdentifier, updateTicketOwner.getEmail(), updateTicketOwner.getFullName());

		//
		sendTicketByEmail(eventName, reservationId, ticketIdentifier, request, response);
		//
		
		return "redirect:/event/" + eventName + "/reservation/" + reservationId;
	}

	@RequestMapping(value = "/event/{eventName}/reservation/{reservationId}/{ticketIdentifier}/send-ticket-by-email", method = RequestMethod.POST)
	public String sendTicketByEmail(@PathVariable("eventName") String eventName,
			@PathVariable("reservationId") String reservationId, 
			@PathVariable("ticketIdentifier") String ticketIdentifier, HttpServletRequest request, HttpServletResponse response) throws Exception {
		
		
		Triple<Event, TicketReservation, Ticket> data = fetch(eventName, reservationId, ticketIdentifier);
		check(data.getMiddle(), data.getRight());
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		preparePdfTicket(request, response, data.getLeft(), data.getRight()).createPDF(baos);
		
		Attachment attachment = new Attachment("ticket-" + ticketIdentifier + ".pdf", new ByteArrayResource(baos.toByteArray()), "application/pdf");
		
		//TODO: complete
		mailManager.mailer().send(data.getRight().getEmail(), "your ticket", "here attached your ticket", Optional.of("here attached your ticket"), attachment);
		
		return "redirect:/event/" + eventName + "/reservation/" + reservationId;
	}
	

	@RequestMapping(value = "/event/{eventName}/reservation/{reservationId}/{ticketIdentifier}/download-ticket", method = RequestMethod.GET)
	public void generateTicketPdf(@PathVariable("eventName") String eventName,
			@PathVariable("reservationId") String reservationId,
			@PathVariable("ticketIdentifier") String ticketIdentifier, HttpServletRequest request, HttpServletResponse response)
			throws Exception {

		Triple<Event, TicketReservation, Ticket> data = fetch(eventName, reservationId, ticketIdentifier);
		Ticket ticket = data.getRight();
		
		check(data.getMiddle(), ticket);

		response.setContentType("application/pdf");
		response.addHeader("Content-Disposition", "attachment; filename=ticket-" + ticketIdentifier + ".pdf");
		try (OutputStream os = response.getOutputStream()) {
			preparePdfTicket(request, response, data.getLeft(), ticket).createPDF(os);
		}
	}
	
	@RequestMapping(value = "/event/{eventName}/reservation/{reservationId}/{ticketIdentifier}/code.png", method = RequestMethod.GET)
	public void generateTicketCode(@PathVariable("eventName") String eventName,
			@PathVariable("reservationId") String reservationId,
			@PathVariable("ticketIdentifier") String ticketIdentifier, HttpServletResponse response) throws IOException, WriterException {
		
		Triple<Event, TicketReservation, Ticket> data = fetch(eventName, reservationId, ticketIdentifier);
		Event event = data.getLeft();
		Ticket ticket = data.getRight();
		
		check(data.getMiddle(), ticket);
		
		String qrCodeText =  ticket.ticketCode(event.getPrivateKey());
		
		response.setContentType("image/png");
		response.getOutputStream().write(createQRCode(qrCodeText));
		
	}
	
	private void check(TicketReservation reservation, Ticket ticket) {
		Validate.isTrue(reservation.getStatus() == TicketReservationStatus.COMPLETE);
		Validate.isTrue(ticket.getAssigned(), "can only generate a pdf if the ticket is assigned to a person");
	}
	
	
	private Triple<Event, TicketReservation, Ticket> fetch(String eventName, String reservationId, String ticketIdentifier) {
		Event event = optionally(() -> eventRepository.findByShortName(eventName)).orElseThrow(
				IllegalArgumentException::new);
		TicketReservation reservation = optionally(() -> ticketReservationRepository.findReservationById(reservationId))
				.orElseThrow(IllegalArgumentException::new);
		Ticket ticket = optionally(() -> ticketRepository.findByUUID(ticketIdentifier)).orElseThrow(
				IllegalArgumentException::new);
		return Triple.of(event, reservation, ticket);
	}

	private ITextRenderer preparePdfTicket(HttpServletRequest request, HttpServletResponse response, Event event, Ticket ticket)
			throws Exception {
		
		TicketCategory ticketCategory = ticketCategoryRepository.getById(ticket.getCategoryId());
		
		//
		String qrCodeText =  ticket.ticketCode(event.getPrivateKey());
		//
		
		//
		//TODO add event organizer and display it :D
		//

		Map<String, Object> tmplModel = new HashMap<>();
		tmplModel.put("ticket", ticket);
		tmplModel.put("ticketCategory", ticketCategory);
		tmplModel.put("event", event);
		tmplModel.put("qrCodeDataUri", toDataUri(createQRCode(qrCodeText)));
		tmplModel.put("format-date", DateFormatterInterceptor.FORMAT_DATE);
		
		// TODO: ugly :(
		ModelAndView mv = new ModelAndView("ticket.ms", tmplModel);
		localizationMessageInterceptor.postHandle(request, response, null, mv);
		//
		tmplModel = mv.getModel();

		InputStreamReader ticketTmpl = new InputStreamReader(
				new ClassPathResource("/io/bagarino/templates/ticket.ms").getInputStream(), StandardCharsets.UTF_8);
		String page = Mustache.compiler().escapeHTML(false).withFormatter((o) -> {
							return (o instanceof Date) ? DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format((Date) o)
									: String.valueOf(o);
						}).compile(ticketTmpl).execute(tmplModel);

		ITextRenderer renderer = new ITextRenderer();
		renderer.setDocumentFromString(page);
		renderer.layout();
		return renderer;
	}

	private static byte[] createQRCode(String text) throws WriterException, IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Map<EncodeHintType, Object> hintMap = new EnumMap<>(EncodeHintType.class);
		hintMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
		BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 200, 200, hintMap);
		MatrixToImageWriter.writeToStream(matrix, "png", baos);
		return baos.toByteArray();
	}

	private static String toDataUri(byte[] content) {
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(content);
	}
	
	@Data
	public static class UpdateTicketOwnerForm {
		private String email;
		private String fullName;
	}
}
